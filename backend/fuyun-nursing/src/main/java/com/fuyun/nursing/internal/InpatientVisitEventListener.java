package com.fuyun.nursing.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 住院就诊事件族消费侧（M05 执行域联动，Task 4 五路）：visit.admitted / visit.transferred /
 * visit.discharge-requested / visit.discharged / bed.changed——转科路承载执行单重定向
 * （未执行态随患者切新病区，「转科三分规则」M04 侧⑤）、出院终清路承载未执行执行单与在途
 * 任务批量撤销；投影写入四路（admitted upsert / transferred 归属更新 / discharged 逻辑删 /
 * bed.changed 床号更新）本任务先落调用占位方法（Task 7 实装）。队列名由治理构件按
 * q.nursing.&lt;登记名&gt; 统一推导（V800 id 48–52 登记面既有）。
 *
 * <p>幂等双层：eventId 构件幂等（IdempotentConsumerSupport 三段式）+ 业务级幂等（重定向
 * fromWard 谓词 / 终清未执行态谓词——重复投递 0 行自然达成）。载荷以 JsonNode 读（禁依赖
 * inpatient api——GC11 模块依赖单向红线）。终清双 CAS（执行单+任务）各自原子，无外层事务：
 * 部分成功由消费重试收敛（两谓词均幂等，重放零重复副作用）——与既有 nursing 监听器
 * 零事务注解形态一致（PatientMergedListener 先例，@Transactional 会连带回滚三段式 FAILED
 * 留痕登记）。载荷字段缺失/形态违约抛 ISE 进死信留痕。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class InpatientVisitEventListener {

    /** 出院终清撤销原因（固定文案，执行单/任务两路留痕同源） */
    static final String DISCHARGE_CLEANUP_REASON = "出院终清";

    /** 消费链路无登录上下文的操作者（与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    private final IdempotentConsumerSupport consumerSupport;

    private final OrderExecutionMapper orderExecutionMapper;

    private final NursingTaskMapper nursingTaskMapper;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板 @Qualifier 定绑
     * nursingConsumerSupport——GC7 common 模板类多实例红线；单测按位置构造零改动）。
     *
     * @param consumerSupport     消费模板，非空；定绑 NursingMessagingConfig nursingConsumerSupport Bean
     * @param orderExecutionMapper 执行单 mapper，非空；重定向与终清撤销直驱条件更新（监听器薄切片，
     *                            与 PatientMergedListener 直驱 mapper 同款形态）
     * @param nursingTaskMapper   护理任务 mapper，非空；出院终清在途任务批量撤销写面
     */
    public InpatientVisitEventListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            OrderExecutionMapper orderExecutionMapper,
            NursingTaskMapper nursingTaskMapper) {
        this.consumerSupport = consumerSupport;
        this.orderExecutionMapper = orderExecutionMapper;
        this.nursingTaskMapper = nursingTaskMapper;
    }

    /**
     * 患者入科消费入口（q.nursing.inpatient.visit.admitted，V800 id 48）：投影 upsert 占位
     * （Task 7 实装），执行域无动作。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_ADMITTED)
    public void onVisitAdmitted(Message message) {
        consumerSupport.consume(message, this::handleVisitAdmitted);
    }

    /**
     * 患者转科/转床消费入口（q.nursing.inpatient.visit.transferred，V800 id 49）：投影归属
     * 更新占位 + 执行单重定向。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_TRANSFERRED)
    public void onVisitTransferred(Message message) {
        consumerSupport.consume(message, this::handleVisitTransferred);
    }

    /**
     * 出院申请消费入口（q.nursing.inpatient.visit.discharge-requested，V800 id 50）：在途任务
     * 与执行单清退提示（不改状态）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_DISCHARGE_REQUESTED)
    public void onDischargeRequested(Message message) {
        consumerSupport.consume(message, this::handleDischargeRequested);
    }

    /**
     * 患者出院终态消费入口（q.nursing.inpatient.visit.discharged，V800 id 51）：终清（未执行
     * 执行单/在途任务批量 CANCELLED + 投影逻辑删占位）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX
                    + NursingMessagingConstants.EVENT_SUB_INPATIENT_VISIT_DISCHARGED)
    public void onVisitDischarged(Message message) {
        consumerSupport.consume(message, this::handleVisitDischarged);
    }

    /**
     * 床位动态变更消费入口（q.nursing.inpatient.bed.changed，V800 id 52）：投影 bed_no 更新
     * 占位（占床/转入且有患者主体时）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_INPATIENT_BED_CHANGED)
    public void onBedChanged(Message message) {
        consumerSupport.consume(message, this::handleBedChanged);
    }

    /**
     * 入科业务体（包级直驱可测）：投影 upsert 占位——V800 id 48 冻结载荷六字段全量解析后
     * 交占位方法（Task 7 落 nursing_ward_patient upsert 写入面）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 48 冻结
     * @throws IllegalStateException 缺 visitId/patientId/wardId/admittedAt（死信留痕）时触发
     */
    void handleVisitAdmitted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        upsertWardPatientProjection(
                requireText(envelope, payload, "visitId"),
                requirePatientId(envelope, payload),
                requireText(envelope, payload, "wardId"),
                payload.path("bedId").asLong(0),
                requireInstant(envelope, payload, "admittedAt"),
                textOrNull(payload, "nursingLevel"));
    }

    /**
     * 转科/转床业务体（包级直驱可测）：投影归属更新占位 + 未执行执行单重定向（ward 切换、
     * bed_no 随事件、计划时间不动——fromWard 谓词承载重复投递幂等）。V800 id 49 冻结载荷
     * 仅携 toBedId（床位 id）无床位号，bed_no 冗余展示列落 id 文本形态承载。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 49 冻结
     * @throws IllegalStateException 缺定位键或 toBedId 非法（死信留痕）时触发
     */
    void handleVisitTransferred(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireText(envelope, payload, "visitId");
        long patientId = requirePatientId(envelope, payload);
        String fromWardId = requireText(envelope, payload, "fromWardId");
        String toWardId = requireText(envelope, payload, "toWardId");
        long toBedId = payload.path("toBedId").asLong(0);
        if (toBedId <= 0) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "转科载荷不合规（toBedId 缺失或非法）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        Instant transferredAt = requireInstant(envelope, payload, "transferredAt");
        updateProjectionOwnership(
                visitId, patientId, fromWardId, payload.path("fromBedId").asLong(0), toWardId, toBedId, transferredAt);
        // 数据库写操作：执行单重定向 CAS（未执行三态 + fromWard 幂等谓词；计划时间不动）
        int rows = orderExecutionMapper.casRedirectWard(
                visitId, fromWardId, toWardId, String.valueOf(toBedId), SYSTEM_OPERATOR);
        log.info(
                "转科执行单重定向：visitId={}，fromWardId={}，toWardId={}，重定向行数={}（未执行态，计划时间不变）",
                visitId,
                fromWardId,
                toWardId,
                rows);
    }

    /**
     * 出院申请业务体（包级直驱可测）：在途任务与执行单清退提示——不改任何状态（本任务无
     * WS board 推送面，先落提示占位日志；board 推送与任务 remark 追加归后续任务实装）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 50 冻结
     * @throws IllegalStateException 缺 visitId/patientId/requestedAt（死信留痕）时触发
     */
    void handleDischargeRequested(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireText(envelope, payload, "visitId");
        long patientId = requirePatientId(envelope, payload);
        Instant requestedAt = requireInstant(envelope, payload, "requestedAt");
        // TODO(P2-PR3): WS board 推送与在途任务 remark 追加实装（不改状态红线保持——本任务仅清退提示占位）
        log.info("出院申请清退提示：visitId={}，patientId={}，requestedAt={}（在途任务与执行单清退提示，不改状态）", visitId, patientId, requestedAt);
    }

    /**
     * 出院终清业务体（包级直驱可测）：未执行执行单（CREATED/SIGNED/CHECKED，EXECUTING 不动——
     * 归输注中断特殊面）与在途任务（PENDING/IN_PROGRESS）批量 CANCELLED、原因固定「出院终清」；
     * 投影逻辑删占位（Task 7 实装）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 51 冻结
     * @throws IllegalStateException 缺 visitId/patientId/dischargedAt（死信留痕）时触发
     */
    void handleVisitDischarged(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireText(envelope, payload, "visitId");
        requirePatientId(envelope, payload);
        requireInstant(envelope, payload, "dischargedAt");
        // 数据库写操作：未执行执行单终清 CAS（未执行三态谓词，0 行=已终清幂等达成）
        int executions = orderExecutionMapper.casCancelByVisit(visitId, DISCHARGE_CLEANUP_REASON, SYSTEM_OPERATOR);
        // 数据库写操作：在途任务终清 CAS（PENDING/IN_PROGRESS 谓词，与任务域 cancel 状态面同构）
        int tasks = nursingTaskMapper.casCancelByVisit(visitId, DISCHARGE_CLEANUP_REASON, SYSTEM_OPERATOR);
        logicalDeleteProjection(visitId);
        log.info("出院终清：visitId={}，执行单撤销行数={}，任务撤销行数={}（EXECUTING 执行单不动，归输注中断特殊面）", visitId, executions, tasks);
    }

    /**
     * 床位变更业务体（包级直驱可测）：占床/转入且有患者主体（patientId 非空）时走投影 bed_no
     * 更新占位；预占/释放/消毒/维修等无主体场景直返（投影不更新）。执行域无动作。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 52 冻结
     * @throws IllegalStateException 缺 wardId/bedNo（死信留痕）时触发
     */
    void handleBedChanged(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String wardId = requireText(envelope, payload, "wardId");
        long bedId = payload.path("bedId").asLong(0);
        String bedNo = requireText(envelope, payload, "bedNo");
        String bedStatus = textOrNull(payload, "bedStatus");
        long patientId = payload.path("patientId").asLong(0);
        if (patientId <= 0) {
            // 无患者主体（预占/释放/消毒/维修面）：床位与患者投影无关联可更新
            log.info("床位变更无患者主体（投影不更新）：wardId={}，bedNo={}，bedStatus={}", wardId, bedNo, bedStatus);
            return;
        }
        updateProjectionBedNo(wardId, bedId, bedNo, bedStatus, patientId);
    }

    /**
     * 投影 upsert 占位（入科路）：V800 id 48 冻结载荷六字段全量到达。
     *
     * @param visitId      住院就诊号，非空；来源：事件载荷
     * @param patientId    患者主索引，非空；来源：事件载荷
     * @param wardId       入科病区编码，非空；来源：事件载荷
     * @param bedId        入科床位 id，非空（本占位面不做强校验）；来源：事件载荷
     * @param admittedAt   入科确认时点，非空；来源：事件载荷
     * @param nursingLevel 护理级别 code，可空；来源：事件载荷
     */
    // TODO(P2-PR3-Task7): 病区患者投影 admitted upsert 写入面（nursing_ward_patient，uk_ward_patient_visit 唯一兜底）
    private void upsertWardPatientProjection(
            String visitId, long patientId, String wardId, long bedId, Instant admittedAt, String nursingLevel) {
        log.info(
                "入科投影写入占位（Task 7 实装）：visitId={}，patientId={}，wardId={}，bedId={}，nursingLevel={}",
                visitId,
                patientId,
                wardId,
                bedId,
                nursingLevel);
    }

    /**
     * 投影归属更新占位（转科路）：V800 id 49 冻结载荷七字段全量到达。
     *
     * @param visitId       住院就诊号，非空；来源：事件载荷
     * @param patientId     患者主索引，非空；来源：事件载荷
     * @param fromWardId    转出病区编码，非空；来源：事件载荷
     * @param fromBedId     转出床位 id，非空（占位面不强校验）；来源：事件载荷
     * @param toWardId      转入病区编码，非空；来源：事件载荷
     * @param toBedId       转入床位 id，非空；来源：事件载荷
     * @param transferredAt 转移完成时点，非空；来源：事件载荷
     */
    // TODO(P2-PR3-Task7): 病区患者投影归属更新写入面（ward/bed 随转移事件切换）
    private void updateProjectionOwnership(
            String visitId,
            long patientId,
            String fromWardId,
            long fromBedId,
            String toWardId,
            long toBedId,
            Instant transferredAt) {
        log.info(
                "转科投影归属更新占位（Task 7 实装）：visitId={}，patientId={}，fromWardId={}，toWardId={}，toBedId={}",
                visitId,
                patientId,
                fromWardId,
                toWardId,
                toBedId);
    }

    /**
     * 投影逻辑删占位（出院终清路）：V1108 后 nursing_ward_patient 在区语义由 deleted 承载。
     *
     * @param visitId 住院就诊号，非空；来源：事件载荷
     */
    // TODO(P2-PR3-Task7): 病区患者投影出院逻辑删写入面（deleted=1，禁物理删——W-34 退役口径）
    private void logicalDeleteProjection(String visitId) {
        log.info("出院投影逻辑删占位（Task 7 实装）：visitId={}", visitId);
    }

    /**
     * 投影床号更新占位（床位变更路）：占床/转入且有患者主体时到达。
     *
     * @param wardId    床位归属病区编码，非空；来源：事件载荷
     * @param bedId     床位 id，非空（占位面不强校验）；来源：事件载荷
     * @param bedNo     床号，非空；来源：事件载荷
     * @param bedStatus 迁移后床位状态 code，可空；来源：事件载荷
     * @param patientId 占用患者主索引，非空（无主体已在调用前直返）；来源：事件载荷
     */
    // TODO(P2-PR3-Task7): 病区患者投影 bed_no 更新写入面（占床/转入命中投影行时）
    private void updateProjectionBedNo(String wardId, long bedId, String bedNo, String bedStatus, long patientId) {
        log.info(
                "床位变更投影床号更新占位（Task 7 实装）：wardId={}，bedNo={}，bedStatus={}，patientId={}",
                wardId,
                bedNo,
                bedStatus,
                patientId);
    }

    /**
     * 载荷必填文本守卫（缺失/空白即不合规帧，显式抛出进死信留痕）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 字段文本值，非空
     */
    private static String requireText(EventEnvelope envelope, JsonNode payload, String field) {
        String value = textOrNull(payload, field);
        if (value == null) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "就诊事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return value;
    }

    /**
     * 载荷 patientId 守卫（缺失/非法即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @return 患者主索引，非空
     */
    private static long requirePatientId(EventEnvelope envelope, JsonNode payload) {
        long patientId = payload.path("patientId").asLong(0);
        if (patientId <= 0) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "就诊事件载荷不合规（patientId 缺失或非法）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        return patientId;
    }

    /**
     * 载荷必填时点守卫（ISO-8601 Instant 文本；缺失/不可解析即不合规帧）。
     *
     * @param envelope 事件信封，非空
     * @param payload  载荷 JSON，非空
     * @param field    字段名，非空
     * @return 时点，非空
     */
    private static Instant requireInstant(EventEnvelope envelope, JsonNode payload, String field) {
        String value = textOrNull(payload, field);
        if (value == null) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "就诊事件载荷不合规（缺 " + field + "）：eventType=" + envelope.eventType() + "，payload=" + payload);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            // EX-19 C 类留痕：内部 MQ 帧契约守卫（消费侧，非用户输入路径），ISE 进死信留痕
            throw new IllegalStateException(
                    "就诊事件载荷不合规（" + field + " 非法时点文本）：eventType=" + envelope.eventType() + "，payload=" + payload, e);
        }
    }

    /**
     * 载荷文本字段宽松读取（缺失/空白返回 null）。
     *
     * @param payload 载荷 JSON，非空
     * @param field   字段名，非空
     * @return 字段文本值，可空
     */
    private static String textOrNull(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }
}
