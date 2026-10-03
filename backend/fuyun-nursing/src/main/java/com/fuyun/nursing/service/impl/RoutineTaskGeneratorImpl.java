package com.fuyun.nursing.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.constants.TimeConstants;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.entity.NursingTask;
import com.fuyun.nursing.entity.NursingWardConfig;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.enums.TaskPriority;
import com.fuyun.nursing.enums.TaskSource;
import com.fuyun.nursing.enums.TaskStatus;
import com.fuyun.nursing.enums.TaskType;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.NursingWardConfigMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.service.IRoutineTaskGenerator;
import com.fuyun.nursing.vo.RoutineTaskGenerateVO;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 常规模板批量生成服务实现（P2 PR-3 Task 9）：V1107 routine_task_templates JSONB 列契约
 * [{templateCode,name,frequencyMinutes,taskType}] 消费（列默认空数组——种子缺位时零模板
 * 零生成，报告注记）；在区患者投影=ward_patient deleted=0（W-34 退役后单一在册语义，
 * @TableLogic 自动过滤）。幂等去重=（templateCode+visitId+planTime）查重集合判重
 * （nursing_task 无业务 uk 不加迁移——planTime 以 Instant 归一入键，防 DB 往返 offset
 * 表示差异漏判）；逐行独立落库无外层事务（PG 约束冲突不毒化批次，任务号唯一冲突幂等跳过
 * ——Task 4 终清零事务先例）。生成行零事件发布（批量生成非任务生命周期动作——巡视打卡
 * 同款口径）。当日窗口一律北京钟面（HEALTHCARE_TZ 时区红线）。
 * 线程安全：无状态 singleton。
 */
@Slf4j
public class RoutineTaskGeneratorImpl implements IRoutineTaskGenerator {

    /** 系统链路等无登录上下文场景的操作者回退值（与 V805 审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 业务去重键分隔符（templateCode|visitId|planTimeInstant 三段拼接） */
    private static final String DEDUP_SEPARATOR = "|";

    private final NursingWardConfigMapper wardConfigMapper;

    private final NursingWardPatientMapper wardPatientMapper;

    private final NursingTaskMapper taskMapper;

    private final NursingSeqGate seqGate;

    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 NursingWebConfig @Import）。
     *
     * @param wardConfigMapper  病区护理配置 mapper，非空；模板 JSONB 读取面
     * @param wardPatientMapper 病区患者投影 mapper，非空；在区患者读取面
     * @param taskMapper        护理任务 mapper，非空；查重集合读取与逐行落库面
     * @param seqGate           业务单号发号器，非空；任务号 TK 段统一取号出口
     * @param objectMapper      Boot 全局 JSON 序列化器，非空；模板 JSONB 解析
     */
    public RoutineTaskGeneratorImpl(
            NursingWardConfigMapper wardConfigMapper,
            NursingWardPatientMapper wardPatientMapper,
            NursingTaskMapper taskMapper,
            NursingSeqGate seqGate,
            ObjectMapper objectMapper) {
        this.wardConfigMapper = wardConfigMapper;
        this.wardPatientMapper = wardPatientMapper;
        this.taskMapper = taskMapper;
        this.seqGate = seqGate;
        this.objectMapper = objectMapper;
    }

    /**
     * 按病区常规模板批量生成任务：流程与守卫链见接口注。
     *
     * @param wardId 病区编码，非空；来源：生成请求
     * @param date   生成日期，可空（空=北京钟面当日）
     * @return 生成结果（本轮实际新生成行数），非空
     * @throws BizException NS-1016（ward_config 无配置行）/ NS-1019（模板 JSON 或字段不合规）
     */
    @Override
    public RoutineTaskGenerateVO generateForWard(String wardId, LocalDate date) {
        // 数据库读操作：病区配置行定位（一病区一行；逻辑删由 @TableLogic 自动过滤）
        NursingWardConfig config = wardConfigMapper.selectOne(
                new LambdaQueryWrapper<NursingWardConfig>().eq(NursingWardConfig::getWardId, wardId));
        if (config == null) {
            throw new BizException(
                    NursingErrorCode.CONFLICT, HttpStatus.CONFLICT, "未知病区（无护理配置行），禁止生成常规模板任务：wardId=" + wardId);
        }
        List<JsonNode> templates = parseTemplates(wardId, config.getRoutineTaskTemplates());
        if (templates.isEmpty()) {
            // 种子缺位注记：V1107 列默认空数组——零模板零生成（配置面由病区管理维护，非本生成器职责）
            log.info("病区常规模板为空，零生成：wardId={}", wardId);
            return new RoutineTaskGenerateVO(0);
        }
        // 数据库读操作：在区患者投影（deleted=0 即在区——W-34 退役后单一在册语义，禁引用已退役 status 列）
        List<NursingWardPatient> patients = wardPatientMapper.selectList(
                new LambdaQueryWrapper<NursingWardPatient>().eq(NursingWardPatient::getWardId, wardId));
        if (patients.isEmpty()) {
            log.info("病区无在区患者，零生成：wardId={}", wardId);
            return new RoutineTaskGenerateVO(0);
        }
        LocalDate targetDate = date == null ? LocalDate.now(TimeConstants.HEALTHCARE_TZ) : date;
        ZoneId zone = TimeConstants.HEALTHCARE_TZ;
        OffsetDateTime now = OffsetDateTime.now(zone);
        OffsetDateTime dayStart = targetDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        // 当日余下面起点：已过时刻的切片不再生成（当日=now 起；未来日期=全日；过往日期=空窗）
        OffsetDateTime windowStart = dayStart.isAfter(now) ? dayStart : now;
        Set<String> existingKeys = loadExistingKeys(wardId, dayStart, dayEnd);
        String operator = operator();
        int created = 0;
        for (JsonNode template : templates) {
            String templateCode = template.path("templateCode").asText();
            int frequencyMinutes = template.path("frequencyMinutes").asInt();
            String taskTypeCode = template.path("taskType").asText();
            // 切片自当日 00:00 北京钟面锚定步进（与全院排班钟面对齐），仅保留不早于 windowStart 的余下片
            for (OffsetDateTime slice = dayStart; slice.isBefore(dayEnd); slice = slice.plusMinutes(frequencyMinutes)) {
                if (slice.isBefore(windowStart)) {
                    continue;
                }
                for (NursingWardPatient patient : patients) {
                    // 幂等业务键判重（templateCode+visitId+planTime，Instant 归一防 offset 表示差异漏判）
                    if (existingKeys.contains(dedupKey(templateCode, patient.getVisitId(), slice))) {
                        continue;
                    }
                    if (insertRoutineTask(templateCode, taskTypeCode, patient, slice, operator)) {
                        created++;
                    }
                }
            }
        }
        log.info(
                "常规模板任务批量生成完成：wardId={}，date={}，模板数={}，在区患者数={}，新生成={}",
                wardId,
                targetDate,
                templates.size(),
                patients.size(),
                created);
        return new RoutineTaskGenerateVO(created);
    }

    /**
     * 模板 JSONB 解析与字段校验（列契约 [{templateCode,name,frequencyMinutes,taskType}]，
     * V1107 列注释冻结）：JSON 不合规或字段非法（code 空白/频次非正/任务类型不在词表）显式
     * NS-1019 拒绝——配置数据错误在触发时点暴露而非静默吞（运维可定位）。
     *
     * @param wardId    病区编码（异常消息定位锚），非空
     * @param templates 模板 JSON 文本（pgjdbc getString 直读），可空（空按空数组处理）
     * @return 模板节点清单（空数组/空白文本返回空清单），非空
     * @throws BizException NS-1019（400 JSON 不合规或模板字段非法）时触发
     */
    private List<JsonNode> parseTemplates(String wardId, String templates) {
        if (templates == null || templates.isBlank()) {
            return List.of();
        }
        JsonNode array;
        try {
            array = objectMapper.readTree(templates);
        } catch (Exception e) {
            throw new BizException(
                    NursingErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "病区常规模板 JSON 不合规：wardId=" + wardId);
        }
        List<JsonNode> validated = new ArrayList<>();
        for (JsonNode template : array) {
            String templateCode = template.path("templateCode").asText();
            String taskTypeCode = template.path("taskType").asText();
            int frequencyMinutes = template.path("frequencyMinutes").asInt();
            // 字段合法性：code 非空白（幂等键承载）、频次为正（切片步进不可为零）、任务类型在 TaskType 词表
            if (templateCode.isBlank() || frequencyMinutes <= 0 || TaskType.fromCode(taskTypeCode) == null) {
                throw new BizException(
                        NursingErrorCode.PARAM_FORMAT_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "病区常规模板字段不合规（templateCode/frequencyMinutes/taskType）：wardId=" + wardId + "，templateCode="
                                + templateCode);
            }
            validated.add(template);
        }
        return validated;
    }

    /**
     * 既有业务键集合加载（幂等查重面）：病区当日 ROUTINE 源任务行三键投影查询——
     * templateCode（source_ref）+visitId+planTime（Instant 归一）拼键入集。
     *
     * @param wardId    病区编码，非空
     * @param dayStart  当日窗口起点（北京钟面），非空
     * @param dayEnd    当日窗口终点（含头不含尾），非空
     * @return 既有业务键集合（无行返回空集），非空
     */
    private Set<String> loadExistingKeys(String wardId, OffsetDateTime dayStart, OffsetDateTime dayEnd) {
        // 数据库读操作：当日既有 ROUTINE 行三键投影（逻辑删由 @TableLogic 自动过滤）
        List<NursingTask> existing = taskMapper.selectList(new LambdaQueryWrapper<NursingTask>()
                .eq(NursingTask::getWardId, wardId)
                .eq(NursingTask::getSource, TaskSource.ROUTINE.getCode())
                .ge(NursingTask::getPlanTime, dayStart)
                .lt(NursingTask::getPlanTime, dayEnd));
        Set<String> keys = new HashSet<>();
        for (NursingTask row : existing) {
            keys.add(row.getSourceRef()
                    + DEDUP_SEPARATOR
                    + row.getVisitId()
                    + DEDUP_SEPARATOR
                    + row.getPlanTime().toInstant());
        }
        return keys;
    }

    /**
     * 单行常规任务落库（逐行独立提交——无外层事务，PG 约束冲突不毒化批次）：任务号唯一冲突
     * 幂等跳过（发号器异常回绕极端场景，warn 留痕计数不含）。
     *
     * @param templateCode 模板编码（source_ref 承载——幂等键与来源追溯），非空
     * @param taskTypeCode 任务类型 code（TaskType 词表已校验），非空
     * @param patient      在区患者投影行，非空
     * @param slice        计划时间切片，非空
     * @param operator     操作者（REST 链路登录操作者/系统回退），非空
     * @return true=落库成功；false=任务号唯一冲突幂等跳过
     */
    private boolean insertRoutineTask(
            String templateCode,
            String taskTypeCode,
            NursingWardPatient patient,
            OffsetDateTime slice,
            String operator) {
        NursingTask row = new NursingTask();
        row.setTaskNo(seqGate.nextNo("TK"));
        row.setPatientId(patient.getPatientId());
        row.setVisitId(patient.getVisitId());
        row.setWardId(patient.getWardId());
        row.setBedNo(patient.getBedNo());
        row.setTaskType(taskTypeCode);
        row.setSource(TaskSource.ROUTINE.getCode());
        row.setSourceRef(templateCode);
        row.setPlanTime(slice);
        row.setPriority(TaskPriority.NORMAL.getCode());
        row.setOverdueFlag(false);
        row.setEscalationCount(0);
        row.setStatus(TaskStatus.PENDING.getCode());
        row.setCreatedBy(operator);
        row.setUpdatedBy(operator);
        try {
            // 数据库写操作：常规任务落库（逐行独立事务——无外层事务承载）
            taskMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            // 任务号唯一冲突幂等跳过：不阻断批次（零外层事务下冲突行独立失败零毒化）
            log.warn(
                    "常规任务落库任务号唯一冲突（幂等跳过）：taskNo={}，templateCode={}，visitId={}",
                    row.getTaskNo(),
                    templateCode,
                    patient.getVisitId());
            return false;
        }
    }

    /**
     * 幂等业务键拼接（templateCode+visitId+planTime——planTime 以 Instant 归一，防 DB 往返
     * offset 表示差异漏判）。
     *
     * @param templateCode 模板编码，非空
     * @param visitId      住院就诊号，非空
     * @param planTime     计划时间切片，非空
     * @return 业务键文本，非空
     */
    private static String dedupKey(String templateCode, String visitId, OffsetDateTime planTime) {
        return templateCode + DEDUP_SEPARATOR + visitId + DEDUP_SEPARATOR + planTime.toInstant();
    }

    /** 操作者取值（免登录上下文回退 system，与审计列默认同源）。 */
    private String operator() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }
}
