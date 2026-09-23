package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.AssessmentCompletedPayload;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.constants.NursingScaleConstants;
import com.fuyun.nursing.constants.ScaleDefinition;
import com.fuyun.nursing.dto.NursingAssessmentCreateRequest;
import com.fuyun.nursing.dto.NursingTaskCreateRequest;
import com.fuyun.nursing.entity.NursingAssessment;
import com.fuyun.nursing.enums.RiskLevel;
import com.fuyun.nursing.enums.ScaleType;
import com.fuyun.nursing.enums.TaskPriority;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingAssessmentMapper;
import com.fuyun.nursing.service.INursingAssessmentService;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingAssessmentVO;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.ScaleDefinitionVO;
import com.fuyun.nursing.vo.WardPatientDetailVO;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 护理评估单域服务实现（V806 nursing_assessment 业务面，五量表引擎）。创建八件套（量表类型
 * 校验 → 条目校验 → 判级 → 在区与业务时间校验 → 发号 → insert → 高危联动 → 评估完成事件）
 * 收口单事务：高危联动的防范任务生成（INursingTaskService#create）与风险标识回写
 * （IWardMetaService#appendRiskFlag）任一失败整体回滚，禁单侧落库（联动原子性）。判级阈值
 * 逐值冻结于 NursingScaleConstants；业务时间双口径（assessed_at 业务时钟 + 审计列服务器时钟，
 * Spec 红线 2 注记）。线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class NursingAssessmentServiceImpl extends ServiceImpl<NursingAssessmentMapper, NursingAssessment>
        implements INursingAssessmentService {

    /** 移出/系统链路等无登录上下文场景的操作者回退值（与 V806 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final NursingSeqGate seqGate;

    private final IWardMetaService wardMetaService;

    private final INursingTaskService taskService;

    private final ApplicationEventPublisher events;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param assessmentMapper 护理评估单 mapper，非空；ServiceImpl 基座 mapper
     * @param seqGate          业务单号发号器，非空；评估单号 AS 段统一取号出口
     * @param wardMetaService  病区元数据服务，非空；在区校验/患者病区归一/高危风险标识回写（Task 3 面）
     * @param taskService      护理任务服务，非空；高危联动防范任务生成（Task 7 面）
     * @param events           Spring 应用事件发布器，非空；事务内发布经 AFTER_COMMIT 出 MQ
     *                       （GC8 红线，NursingTaskServiceImpl 同款进程内桥）
     * @param objectMapper     JSON 解析器（Boot 自动装配），非空；answers JSONB 列结构化
     */
    public NursingAssessmentServiceImpl(
            NursingAssessmentMapper assessmentMapper,
            NursingSeqGate seqGate,
            IWardMetaService wardMetaService,
            INursingTaskService taskService,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.seqGate = seqGate;
        this.wardMetaService = wardMetaService;
        this.taskService = taskService;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * 评估量表定义清单：五量表冻结定义全量暴露，纯内存常量读取（零 DB 触达，无需事务）。
     *
     * @return 量表定义出参清单，非空；按冻结展示序
     */
    @Override
    public List<ScaleDefinitionVO> scales() {
        return NursingScaleConstants.definitions().stream()
                .map(ScaleDefinitionVO::from)
                .toList();
    }

    /**
     * 护理评估单创建（八步链，守卫链见接口注）：判级阈值与条目词表全部取自冻结常量；
     * 高危联动三件事（防范任务/风险标识/引用回填）与本事务评估落库同生共死。评估时点为
     * 临床实际评估时刻（业务时间），落库同时以服务器时间承载审计列（两套口径 Spec 注记登记）。
     *
     * @param req 创建入参，非空；来源：操作者工作站评估表单
     * @return 评估单出参（含判级结果与复评计划），非空
     * @throws BizException NS-1009（400 量表类型不支持）/ NS-1010（400 条目缺失或取值越界）/
     *                      NS-1004（409 患者不在区）/ NS-1016（409 评估时点越界或评估单号唯一冲突幂等拒绝）
     */
    @Override
    @Transactional
    public NursingAssessmentVO create(NursingAssessmentCreateRequest req) {
        // 守卫链①：量表类型显式校验（CUSTOM 等词表外值 NS-1009——自定义引擎归 P2，禁裸值入库）
        ScaleType scaleType = ScaleType.fromCode(req.scaleType());
        if (scaleType == null) {
            throw new BizException(
                    NursingErrorCode.SCALE_TYPE_UNSUPPORTED,
                    HttpStatus.BAD_REQUEST,
                    "评估量表类型不支持（CUSTOM 引擎归 P2）：" + req.scaleType());
        }
        ScaleDefinition definition = NursingScaleConstants.definition(scaleType);
        // 守卫链②③：条目完整性 + 取值范围校验（NS-1010）→ 算总分并按冻结阈值判级
        int totalScore = totalScore(definition, req.answers());
        RiskLevel riskLevel = definition.riskThresholds().apply(totalScore);
        // 守卫链④：在区校验（patientId/wardId 由在区行服务端装配，不信客户端）+ 业务时间双向校验
        WardPatientDetailVO inWard = requireInWard(req.visitId());
        assertBusinessTime(req.assessedAt(), inWard.admittedAt(), req.visitId());
        String operator = operator();
        // 步骤⑤：发号器取 AS 评估单号（AS+yyyyMMdd+5 位流水，多实例 Redis 原子不重号）
        String assessNo = seqGate.nextNo("AS");
        NursingAssessment row = new NursingAssessment();
        row.setAssessNo(assessNo);
        row.setPatientId(inWard.patientId());
        row.setVisitId(req.visitId());
        row.setWardId(inWard.wardId());
        row.setScaleType(scaleType.getCode());
        row.setAnswers(writeAnswers(req.visitId(), req.answers()));
        row.setTotalScore(totalScore);
        row.setRiskLevel(riskLevel.getCode());
        row.setAssessedAt(req.assessedAt());
        row.setAssessedBy(operator);
        // 复评计划按风险等级周期盖章（HIGH 24h / MEDIUM 72h / LOW 168h，以评估时点为基准）
        row.setNextAssessPlan(req.assessedAt().plusHours(NursingScaleConstants.reassessHours(riskLevel)));
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：评估单落库（uk_nursing_assessment_no 部分唯一索引兜底防双写）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 评估单号唯一冲突兜底转业务拒绝（发号器异常回绕等极端并发场景，幂等拒绝不覆盖）
            throw new BizException(
                    NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "评估单号唯一冲突（幂等拒绝）：assessNo=" + assessNo);
        }
        // 步骤⑦：高危分支联动——防范任务生成 + 风险标识回写 + 任务引用回填（任一失败整体回滚）
        if (riskLevel == RiskLevel.HIGH) {
            linkHighRisk(row, scaleType);
        }
        // 步骤⑧：评估完成事件（事务内发布，AFTER_COMMIT 出站 GC8；高危结论供下游联动消费）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_ASSESSMENT_COMPLETED,
                new AssessmentCompletedPayload(
                        row.getPatientId(),
                        row.getVisitId(),
                        row.getAssessNo(),
                        scaleType.getCode(),
                        totalScore,
                        riskLevel.getCode())));
        log.info(
                "护理评估单创建：assessNo={}，visitId={}，wardId={}，scaleType={}，totalScore={}，riskLevel={}，"
                        + "nextAssessPlan={}，operator={}",
                row.getAssessNo(),
                row.getVisitId(),
                row.getWardId(),
                scaleType.getCode(),
                totalScore,
                riskLevel.getCode(),
                row.getNextAssessPlan(),
                operator);
        return NursingAssessmentVO.from(row, req.answers());
    }

    /**
     * 患者评估单清单：visitId 必选、scaleType 可选过滤，DB 侧按评估时点降序（最新评估优先）。
     *
     * @param visitId   住院就诊号，非空；来源：查询参数
     * @param scaleType 量表类型过滤，可空（空=全量表）；来源：查询参数
     * @return 评估单出参清单（无行返回空清单，非 null）；按评估时点降序
     */
    @Override
    @Transactional(readOnly = true)
    public List<NursingAssessmentVO> listByVisit(String visitId, ScaleType scaleType) {
        LambdaQueryWrapper<NursingAssessment> wrapper =
                Wrappers.<NursingAssessment>lambdaQuery().eq(NursingAssessment::getVisitId, visitId);
        if (scaleType != null) {
            wrapper.eq(NursingAssessment::getScaleType, scaleType.getCode());
        }
        wrapper.orderByDesc(NursingAssessment::getAssessedAt);
        // 数据库读操作：患者评估清单（评估时点降序；命中 idx_nursing_assessment_visit；逻辑删自动过滤）
        return baseMapper.selectList(wrapper).stream()
                .map(row -> NursingAssessmentVO.from(row, readAnswers(row)))
                .toList();
    }

    // ===================== 量表引擎与联动内聚方法 =====================

    /**
     * 条目完整性 + 取值范围校验并算总分（步骤②③合一步）：逐冻结条目校验应答存在且分值落在
     * 取值域内，缺失或越界均拒 NS-1010（条目缺失不可判级红线）；总分只对冻结条目求和（词表外
     * 多余键不参与计分，不放大总分）。
     *
     * @param definition 量表冻结定义，非空；来源：NursingScaleConstants
     * @param answers    条目应答，可空（整体缺答按逐条目缺失定性 NS-1010）；来源：请求载荷
     * @return 量表总分（冻结条目分值之和），非负
     * @throws BizException NS-1010（400 条目应答缺失或分值越界）
     */
    private int totalScore(ScaleDefinition definition, Map<String, Integer> answers) {
        int total = 0;
        for (String itemCode : definition.itemCodes()) {
            Integer score = answers == null ? null : answers.get(itemCode);
            // 条目缺失（无应答）与取值越界（超出冻结分值域）同定性 NS-1010：两态均不可判级
            if (score == null || !definition.choices().get(itemCode).contains(score)) {
                throw new BizException(
                        NursingErrorCode.ASSESSMENT_INCOMPLETE,
                        HttpStatus.BAD_REQUEST,
                        "评估条目缺失或取值越界，不可判级：scaleType=" + definition.scaleType().getCode() + "，itemCode=" + itemCode);
            }
            total += score;
        }
        return total;
    }

    /**
     * 高危联动（步骤⑦，仅 riskLevel=HIGH 触达）：①防范任务生成（PREVENTION/ASSESSMENT/HIGH，
     * sourceRef=评估单号，planTime=生成时刻即服务器时间）→ ②床旁风险标识回写（仅 BRADEN→
     * PRESSURE、MORSE→FALL，判重追加语义由 appendRiskFlag 内部承载）→ ③triggered_task_ref
     * 回填（同事务单行回写）。三步与评估落库同事务，任一失败整体回滚（禁评估单侧落库悬置）。
     *
     * @param row       已落库的评估单行（方法内回填 triggeredTaskRef），非空
     * @param scaleType 量表类型枚举（风险标识映射键），非空
     * @throws BizException 防范任务生成被拒（NS-1019/NS-1016）或患者已不在区（NS-1004）时原样上抛，
     *                      评估事务整体回滚
     */
    private void linkHighRisk(NursingAssessment row, ScaleType scaleType) {
        // 第三方服务调用（模块内跨域）：高危联动防范任务生成（服务面直调，非 HTTP）
        NursingTaskVO task = taskService.create(new NursingTaskCreateRequest(
                row.getPatientId(),
                row.getVisitId(),
                row.getWardId(),
                null,
                TaskType.PREVENTION.getCode(),
                TaskSource.ASSESSMENT.getCode(),
                row.getAssessNo(),
                OffsetDateTime.now(),
                null,
                TaskPriority.HIGH.getCode()));
        // 床旁风险标识回写：仅压疮/跌倒两类有床旁标识映射（判重追加在 appendRiskFlag 内部，禁服务侧重复拼串）
        String riskFlag = NursingScaleConstants.riskFlagOf(scaleType);
        if (riskFlag != null) {
            wardMetaService.appendRiskFlag(row.getVisitId(), riskFlag);
        }
        row.setTriggeredTaskRef(task.taskNo());
        // 数据库写操作：防范任务引用回填（本事务插入行无并发窗口，非状态流转，无需 CAS）
        baseMapper.updateById(row);
        log.info(
                "评估高危联动完成：assessNo={}，visitId={}，scaleType={}，taskNo={}，riskFlag={}",
                row.getAssessNo(),
                row.getVisitId(),
                scaleType.getCode(),
                task.taskNo(),
                riskFlag == null ? "无（量表无床旁标识映射）" : riskFlag);
    }

    /**
     * 业务时间双向强校验（Spec 红线 2）：评估时点不得晚于服务器当前时间（禁未来时刻倒灌）、
     * 不得早于该 visit 入区时间（禁入区前评估），越界拒 NS-1016；assessed_at 落业务时间口径，
     * 审计列以服务器时间承载（两套时钟口径在 Spec 注记登记）。
     *
     * @param assessedAt 评估时点，非空；来源：请求载荷（临床实际评估时刻）
     * @param admittedAt 入区时间，非空；来源：在区行 admitted_at
     * @param visitId    住院就诊号，非空；拒绝消息业务标识
     * @throws BizException NS-1016（409 评估时点晚于当前时间或早于入区时间）
     */
    private void assertBusinessTime(OffsetDateTime assessedAt, OffsetDateTime admittedAt, String visitId) {
        if (assessedAt.isAfter(OffsetDateTime.now())) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "评估时点不得晚于当前时间：visitId=" + visitId + "，assessedAt=" + assessedAt);
        }
        if (assessedAt.isBefore(admittedAt)) {
            throw new BizException(
                    NursingErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "评估时点不得早于入区时间：visitId=" + visitId + "，assessedAt=" + assessedAt + "，admittedAt=" + admittedAt);
        }
    }

    /** 按 visit_id 校验在区（IWardMetaService detail；查无在区行定性 NS-1004 患者不在区）。 */
    private WardPatientDetailVO requireInWard(String visitId) {
        try {
            return wardMetaService.detail(visitId);
        } catch (BizException e) {
            // 在区行不存在（detail 定性 NS-1001）→ 评估域语境转 NS-1004 患者不在区（Task 4/5/6 同口径）
            if (NursingErrorCode.WARD_PATIENT_NOT_FOUND.equals(e.getErrorCode())) {
                throw new BizException(
                        NursingErrorCode.PATIENT_BLOCKED, HttpStatus.CONFLICT, "患者不在区，禁止评估：visitId=" + visitId);
            }
            throw e;
        }
    }

    /**
     * 条目应答快照序列化（Map → JSONB 文本落库）：Map&lt;String,Integer&gt; 结构固定，序列化失败
     * 仅可能为序列化器故障（编程级异常，fail-fast 不吞）。
     *
     * @param visitId 住院就诊号，非空；异常消息业务标识
     * @param answers 条目应答，非空；来源：请求载荷
     * @return JSONB 文本（{"itemCode": score, ...}），非空
     * @throws IllegalStateException 应答快照序列化失败（序列化器故障，事务整体回滚）
     */
    private String writeAnswers(String visitId, Map<String, Integer> answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("评估应答快照序列化失败：visitId=" + visitId, e);
        }
    }

    /**
     * 条目应答快照反序列化（JSONB 文本 → 结构化 Map）：库内文本损坏属服务端数据异常，
     * 显式暴露不吞（静默丢快照会破坏评估单可追溯性）。
     *
     * @param row 评估单行，非空；assessNo 为异常消息业务标识
     * @return 条目应答结构化值，非空
     * @throws IllegalStateException 应答快照 JSON 损坏（服务端数据异常，显式暴露）
     */
    private Map<String, Integer> readAnswers(NursingAssessment row) {
        try {
            return objectMapper.readValue(row.getAnswers(), new TypeReference<LinkedHashMap<String, Integer>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("评估应答快照解析失败（服务端数据异常）：assessNo=" + row.getAssessNo(), e);
        }
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
