package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.api.ShiftCompletedPayload;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.dto.HandoverCompleteRequest;
import com.fuyun.nursing.dto.HandoverGenerateRequest;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.entity.ShiftHandover;
import com.fuyun.nursing.enums.HandoverStatus;
import com.fuyun.nursing.enums.NursingLevel;
import com.fuyun.nursing.enums.WardPatientStatus;
import com.fuyun.nursing.internal.NursingDomainEvent;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.mapper.ShiftHandoverMapper;
import com.fuyun.nursing.service.INursingTaskService;
import com.fuyun.nursing.service.IShiftHandoverService;
import com.fuyun.nursing.service.IWardMetaService;
import com.fuyun.nursing.vo.NursingTaskVO;
import com.fuyun.nursing.vo.ShiftHandoverVO;
import com.fuyun.nursing.vo.ShiftHandoverVO.PatientSummary;
import com.fuyun.nursing.vo.ShiftHandoverVO.PendingItem;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交接班域服务实现（V807 shift_handover 业务面，SBAR 结构化交接班）。generate 六步链
 * （配置校验 → 在区视图汇总 → 逐患者在途任务 → SBAR 初稿 → 发号 → insert）收口单事务，
 * 生成即盖章交班签名（双签同刻口径的交班侧）；complete 为 CAS 双签终态流转（GC26 @Update
 * 条件更新 + 影响行数判定，0 行 → NS-1013）+ nursing.shift.completed 事件（事务内发布
 * AFTER_COMMIT 出站，GC8）；未完成不阻塞业务（DRAFT 草稿零副作用）。
 * 在区视图汇总经 NursingWardPatientMapper 直查（listByWard 同谓词）——WardPatientVO 为
 * GC39 字段冻结面不含 condition_tags，病情标记计数须触达实体面（IoRecordServiceImpl 注入
 * TemperatureChartEntryMapper 同款先例）。线程安全：无状态 singleton；写操作 @Transactional 收口。
 */
@Slf4j
public class ShiftHandoverServiceImpl extends ServiceImpl<ShiftHandoverMapper, ShiftHandover>
        implements IShiftHandoverService {

    /** 无登录上下文场景的操作者回退值（与 V807 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 待续事项在途输注/未闭环告警 P1 占位空数组（M14/M16 缺位，P2 接入后替换） */
    private static final String EMPTY_JSON_ARRAY = "[]";

    /** 病情标记词表：新入（V801 condition_tags 逗号分隔镜像，P1 过渡通道录入） */
    private static final String TAG_NEW_ADMISSION = "NEW";

    /** 病情标记词表：手术 */
    private static final String TAG_SURGERY = "SURGERY";

    private final NursingWardPatientMapper wardPatientMapper;

    private final IWardMetaService wardMetaService;

    private final INursingTaskService taskService;

    private final NursingSeqGate seqGate;

    private final ApplicationEventPublisher events;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param handoverMapper    交接班 mapper，非空；ServiceImpl 基座 mapper
     * @param wardPatientMapper 病区患者视图 mapper，非空；患者摘要汇总直查实体面（含 condition_tags）
     * @param wardMetaService   病区元数据服务，非空；生成前病区配置校验（无配置行 NS-1016，Task 3 面）
     * @param taskService       护理任务服务，非空；逐患者在途任务收集（Task 7 面，读路径含惰性逾期写）
     * @param seqGate           业务单号发号器，非空；交接班单号 HO 段统一取号出口
     * @param events            Spring 应用事件发布器，非空；事务内发布经 NursingEventPublisher
     *                          AFTER_COMMIT 出 MQ（GC8 红线，NursingTaskServiceImpl 同款进程内桥）
     * @param objectMapper      JSON 解析器（Boot 自动装配），非空；摘要/待续事项 JSONB 列结构化
     */
    public ShiftHandoverServiceImpl(
            ShiftHandoverMapper handoverMapper,
            NursingWardPatientMapper wardPatientMapper,
            IWardMetaService wardMetaService,
            INursingTaskService taskService,
            NursingSeqGate seqGate,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper) {
        this.wardPatientMapper = wardPatientMapper;
        this.wardMetaService = wardMetaService;
        this.taskService = taskService;
        this.seqGate = seqGate;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * 交接班单生成（六步链，守卫链见接口注）：自动汇总快照与 SBAR 初稿同事务落库，交班签名
     * 随生成盖章（P1 单人发起即视同交班方签署完成，双签另一侧归 complete）。
     *
     * @param req 生成入参，非空；来源：操作者工作站表单
     * @return 交接班出参（DRAFT 态），非空
     * @throws BizException NS-1016（409 未知病区无配置行 / 交接班单号唯一冲突幂等拒绝）
     */
    @Override
    @Transactional
    public ShiftHandoverVO generate(HandoverGenerateRequest req) {
        // 本链含 INSERT 落库与在途任务惰性逾期写（inFlightByVisit CAS），禁 readOnly——
        // PG 只读事务内写操作直接报错，且 readOnly 标记经 Spring 默认传播（REQUIRED）波及调用方
        // 步骤①：病区配置校验（无配置行 → NS-1016 未知病区，wardConfig 冻结面实况行为）
        wardMetaService.wardConfig(req.wardId());
        // 步骤②：在区患者视图汇总（listByWard 同谓词直查实体面：仅 IN_WARD 行、床位序）
        List<NursingWardPatient> inWard = wardPatientMapper.selectList(Wrappers.<NursingWardPatient>lambdaQuery()
                .eq(NursingWardPatient::getWardId, req.wardId())
                .eq(NursingWardPatient::getStatus, WardPatientStatus.IN_WARD.getCode())
                .orderByAsc(NursingWardPatient::getBedNo)
                .orderByAsc(NursingWardPatient::getAdmittedAt));
        PatientSummary summary = aggregateSummary(inWard);
        // 步骤③：逐患者在途任务收集待续事项（仅非空患者入选；在途输注/未闭环告警 P1 空数组）
        List<PendingItem> pendingItems = new ArrayList<>();
        for (NursingWardPatient patient : inWard) {
            for (NursingTaskVO task : taskService.inFlightByVisit(patient.getVisitId())) {
                pendingItems.add(new PendingItem(
                        task.taskNo(), task.taskType(), task.planTime(), task.overdueFlag(), patient.getVisitId()));
            }
        }
        // 步骤④：SBAR 初稿文本拼装（中文模板：计数 + 危重患者床位号列表，人工补充的基线）
        String criticalBeds = criticalBedList(inWard);
        String sbarSituation = buildSbarSituation(req.wardId(), summary, criticalBeds);
        String sbarBackground = buildSbarBackground(summary);
        String sbarAssessment = buildSbarAssessment(criticalBeds);
        String sbarRecommendation = buildSbarRecommendation();
        // 步骤⑤：发号器取 HO 交接班单号（HO+yyyyMMdd+5 位流水，多实例 Redis 原子不重号）
        String handoverNo = seqGate.nextNo("HO");
        String operator = operator();
        ShiftHandover row = new ShiftHandover();
        row.setHandoverNo(handoverNo);
        row.setWardId(req.wardId());
        row.setShiftCode(req.shiftCode());
        row.setHandoverDate(LocalDate.now());
        row.setOutgoingNurseId(operator);
        row.setPatientSummary(toJsonb(summary, handoverNo));
        row.setSbarSituation(sbarSituation);
        row.setSbarBackground(sbarBackground);
        row.setSbarAssessment(sbarAssessment);
        row.setSbarRecommendation(sbarRecommendation);
        row.setPendingItems(toJsonb(pendingItems, handoverNo));
        row.setPendingInfusions(EMPTY_JSON_ARRAY);
        row.setUnclosedAlarms(EMPTY_JSON_ARRAY);
        // 双签同刻口径·交班侧：生成即盖章交班签名（服务器时间，GC25）
        row.setOutgoingSignedAt(OffsetDateTime.now());
        row.setStatus(HandoverStatus.DRAFT.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 步骤⑥·数据库写操作：交接班草稿落库（uk_shift_handover_no 部分唯一索引兜底防双写）
            baseMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 单号唯一冲突兜底转业务拒绝（发号器异常回绕等极端并发场景，幂等拒绝不覆盖——
            // 禁同事务重查兜底，PG 语句失败即事务 aborted）
            throw new BizException(
                    NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "交接班单号唯一冲突（幂等拒绝）：handoverNo=" + handoverNo);
        }
        log.info(
                "交接班单生成：handoverNo={}，wardId={}，shiftCode={}，在区={}人，特级={}，病重={}，新入={}，手术={}，待续任务={}条，operator={}",
                handoverNo,
                req.wardId(),
                req.shiftCode(),
                summary.total(),
                summary.specialCount(),
                summary.criticalCount(),
                summary.newAdmissionCount(),
                summary.surgeryCount(),
                pendingItems.size(),
                operator);
        return toVo(row);
    }

    /**
     * 交接班完成（DRAFT/SIGNING → COMPLETED 双签落定）：CAS 单语句（0 行 → NS-1013）→
     * 重查返回 → 发布 nursing.shift.completed（事务内发布 AFTER_COMMIT 出站 GC8）。
     *
     * @param handoverNo 交接班单业务号，非空；来源：路径参数
     * @param req        完成入参（接班护士必填，SBAR 四段可空），非空；来源：操作者补充确认
     * @return 完成后交接班出参，非空
     * @throws BizException NS-1013（409 单不存在或已完成，禁止重复完成）/
     *                      NS-1016（409 CAS 命中后行被并发逻辑删，回读缺失）
     */
    @Override
    @Transactional
    public ShiftHandoverVO complete(String handoverNo, HandoverCompleteRequest req) {
        String operator = operator();
        // 数据库写操作：完成 CAS（DRAFT/SIGNING 可完成；SBAR 四段 null 归一空串——空串保留初稿；
        // 并发重复完成由行数判定兜底）
        if (baseMapper.casComplete(
                        handoverNo,
                        req.incomingNurseId(),
                        orEmpty(req.sbarSituation()),
                        orEmpty(req.sbarBackground()),
                        orEmpty(req.sbarAssessment()),
                        orEmpty(req.sbarRecommendation()),
                        operator)
                == 0) {
            throw new BizException(
                    NursingErrorCode.HANDOVER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "交接班单不存在或已完成，禁止重复完成：handoverNo=" + handoverNo);
        }
        // CAS 成功后的事务内重查合法（事务未 aborted，Task 4/7 审查口径例外分支）
        ShiftHandover row = requireByHandoverNo(handoverNo);
        // 交接班完成事件（事务内发布，AFTER_COMMIT 出站 GC8；M19 工作量统计消费）
        events.publishEvent(new NursingDomainEvent(
                NursingMessagingConstants.EVENT_SHIFT_COMPLETED,
                new ShiftCompletedPayload(
                        row.getHandoverNo(),
                        row.getWardId(),
                        row.getShiftCode(),
                        row.getOutgoingNurseId(),
                        row.getIncomingNurseId())));
        log.info(
                "交接班完成（双签落定）：handoverNo={}，wardId={}，shiftCode={}，outgoingNurseId={}，incomingNurseId={}，operator={}",
                row.getHandoverNo(),
                row.getWardId(),
                row.getShiftCode(),
                row.getOutgoingNurseId(),
                row.getIncomingNurseId(),
                operator);
        return toVo(row);
    }

    /**
     * 病区交接班清单（按日检索，班次升序）：纯读链（无在途任务嵌查、无惰性逾期写），
     * readOnly 只读事务收口。
     *
     * @param wardId 病区编码，非空；来源：查询参数
     * @param date   交接班日期，非空；来源：查询参数（缺省当日）
     * @return 交接班出参清单（无行返回空清单，非 null）；按班次升序
     */
    @Override
    @Transactional(readOnly = true)
    public List<ShiftHandoverVO> listByWard(String wardId, LocalDate date) {
        // 数据库读操作：同病区同日交接班行（跨日行由谓词滤除；命中 idx_shift_handover_ward_date）
        List<ShiftHandover> rows = baseMapper.selectList(Wrappers.<ShiftHandover>lambdaQuery()
                .eq(ShiftHandover::getWardId, wardId)
                .eq(ShiftHandover::getHandoverDate, date)
                .orderByAsc(ShiftHandover::getShiftCode));
        return rows.stream().map(this::toVo).toList();
    }

    /**
     * 患者摘要汇总（Spec :138 流程 5 口径）：在区总数 + 护理级别分布（SPECIAL/CRITICAL 计数）+
     * 病情标记计数（NEW/SURGERY）。今日出院/转出计数 P1 恒 0——V801 病情标记词表无对应 code
     * （CRITICAL/SEVERE/NEW/SURGERY/DELIVERY），M04 事件链 P2 接入后由视图标签驱动（DDL 契约位预置）。
     *
     * @param inWard 在区患者视图行清单（床位序），非空
     * @return 患者摘要快照，非空
     */
    private PatientSummary aggregateSummary(List<NursingWardPatient> inWard) {
        int specialCount = 0;
        int criticalCount = 0;
        int newAdmissionCount = 0;
        int surgeryCount = 0;
        for (NursingWardPatient patient : inWard) {
            // 护理级别分布（NursingLevel code 精确等值比对）
            if (NursingLevel.SPECIAL.getCode().equals(patient.getNursingLevel())) {
                specialCount++;
            }
            if (NursingLevel.CRITICAL.getCode().equals(patient.getNursingLevel())) {
                criticalCount++;
            }
            // 病情标记计数（逗号分隔镜像文本 contains 比对——词表五 code 互非子串，无歧义）
            String tags = orEmpty(patient.getConditionTags());
            if (tags.contains(TAG_NEW_ADMISSION)) {
                newAdmissionCount++;
            }
            if (tags.contains(TAG_SURGERY)) {
                surgeryCount++;
            }
        }
        return new PatientSummary(inWard.size(), specialCount, criticalCount, newAdmissionCount, surgeryCount, 0, 0);
    }

    /** 危重患者床位号列表（SPECIAL/CRITICAL 在区行，床位序顿号拼接；空集回「无」）。 */
    private String criticalBedList(List<NursingWardPatient> inWard) {
        List<String> beds = inWard.stream()
                .filter(patient -> NursingLevel.SPECIAL.getCode().equals(patient.getNursingLevel())
                        || NursingLevel.CRITICAL.getCode().equals(patient.getNursingLevel()))
                .map(NursingWardPatient::getBedNo)
                .toList();
        return beds.isEmpty() ? "无" : String.join("、", beds);
    }

    /** S 现状初稿：在区总数 + 特级/病重计数 + 危重患者床位号列表（Spec :138 自动汇总口径）。 */
    private String buildSbarSituation(String wardId, PatientSummary summary, String criticalBeds) {
        return "本班" + wardId + "病区在区 " + summary.total() + " 人，其中特级护理 " + summary.specialCount() + " 人、病重护理 "
                + summary.criticalCount() + " 人；危重患者床位：" + criticalBeds + "。";
    }

    /** B 背景初稿：本班病区动态计数（新入/手术/今日出院/转出）。 */
    private String buildSbarBackground(PatientSummary summary) {
        return "本班病区动态：新入 " + summary.newAdmissionCount() + " 人、手术 " + summary.surgeryCount() + " 人、今日出院 "
                + summary.todayDischargeCount() + " 人、转出 " + summary.transferOutCount() + " 人。";
    }

    /** A 评估初稿：模板文本（人工补充确认基线，指向危重床位逐床核实）。 */
    private String buildSbarAssessment(String criticalBeds) {
        return "本班患者整体情况请结合特级/病重患者（床位：" + criticalBeds + "）逐床核实病情变化、风险标识与在途治疗任务后确认。";
    }

    /** R 建议初稿：模板文本（待续事项确认与床旁交接指引）。 */
    private String buildSbarRecommendation() {
        return "请接班护士逐项确认待续事项清单，优先跟进逾期任务，并对特级/病重患者执行床旁交接与双签确认。";
    }

    /**
     * 实体→出参（JSONB 快照列服务端结构化）：摘要/待续事项/占位列解析为结构化值，
     * 损坏定性服务端数据异常显式暴露（静默丢快照会破坏交接班可追溯性）。
     *
     * @param row 交接班行，非空
     * @return 交接班出参，非空
     */
    private ShiftHandoverVO toVo(ShiftHandover row) {
        return new ShiftHandoverVO(
                row.getId(),
                row.getHandoverNo(),
                row.getWardId(),
                row.getShiftCode(),
                row.getHandoverDate(),
                row.getOutgoingNurseId(),
                row.getIncomingNurseId(),
                readJsonb(row.getPatientSummary(), new TypeReference<PatientSummary>() {}, row.getHandoverNo()),
                row.getSbarSituation(),
                row.getSbarBackground(),
                row.getSbarAssessment(),
                row.getSbarRecommendation(),
                readJsonb(row.getPendingItems(), new TypeReference<List<PendingItem>>() {}, row.getHandoverNo()),
                readJsonb(row.getPendingInfusions(), new TypeReference<List<Object>>() {}, row.getHandoverNo()),
                readJsonb(row.getUnclosedAlarms(), new TypeReference<List<Object>>() {}, row.getHandoverNo()),
                row.getOutgoingSignedAt(),
                row.getIncomingSignedAt(),
                row.getStatus());
    }

    /**
     * 快照序列化（结构化值 → JSONB 文本）：序列化失败仅可能为序列化器故障（编程级异常，
     * fail-fast 不吞，事务整体回滚）。
     *
     * @param value      结构化快照值，非空
     * @param handoverNo 交接班单业务号（异常消息业务标识），非空
     * @return JSONB 文本，非空
     * @throws IllegalStateException 快照序列化失败（序列化器故障，事务整体回滚）
     */
    private String toJsonb(Object value, String handoverNo) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("交接班快照序列化失败：handoverNo=" + handoverNo, e);
        }
    }

    /**
     * 快照反序列化（JSONB 文本 → 结构化值）：库内文本损坏属服务端数据异常，显式暴露不吞。
     *
     * @param json       JSONB 文本，非空
     * @param type       目标结构类型引用，非空
     * @param handoverNo 交接班单业务号（异常消息业务标识），非空
     * @param <T>        目标结构类型
     * @return 结构化快照值，非空
     * @throws IllegalStateException 快照 JSON 损坏（服务端数据异常，显式暴露）
     */
    private <T> T readJsonb(String json, TypeReference<T> type, String handoverNo) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("交接班 JSONB 列解析失败（服务端数据异常）：handoverNo=" + handoverNo, e);
        }
    }

    /** 按单号回读交接班行（逻辑删由 @TableLogic 自动过滤；未命中定性 NS-1016）。 */
    private ShiftHandover requireByHandoverNo(String handoverNo) {
        ShiftHandover row = baseMapper.selectOne(
                Wrappers.<ShiftHandover>lambdaQuery().eq(ShiftHandover::getHandoverNo, handoverNo));
        if (row == null) {
            // CAS 与回读间被并发逻辑删的极端窗口：资源已不存在，禁继续出事件
            throw new BizException(NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "交接班单不存在：handoverNo=" + handoverNo);
        }
        return row;
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /** 空串归一（SBAR 四段可空入参 → CAS 空串保留初稿语义的承载）。 */
    private String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
