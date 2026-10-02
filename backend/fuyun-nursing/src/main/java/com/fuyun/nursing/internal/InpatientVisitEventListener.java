package com.fuyun.nursing.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.nursing.constants.NursingMessagingConstants;
import com.fuyun.nursing.entity.NursingWardPatient;
import com.fuyun.nursing.mapper.NursingTaskMapper;
import com.fuyun.nursing.mapper.NursingWardPatientMapper;
import com.fuyun.nursing.mapper.OrderExecutionMapper;
import com.fuyun.nursing.vo.NurseBoardPushFrame;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

/**
 * 住院就诊事件族消费侧（M05 执行域联动 + 病区患者投影写入单一面，Task 7 四路实装）：
 * visit.admitted（投影 upsert）/ visit.transferred（投影归属更新 + 执行单重定向——未执行态随
 * 患者切新病区，「转科三分规则」M04 侧⑤）/ visit.discharge-requested（清退提示不改状态）/
 * visit.discharged（终清：未执行执行单与在途任务批量撤销 + 投影逻辑删）/ bed.changed（投影
 * 床号补齐——床号文本唯一写入面）。队列名由治理构件按 q.nursing.&lt;登记名&gt; 统一推导
 * （V800 id 48–52 登记面既有）。
 *
 * <p><b>投影床号语义（W-34 裁决）</b>：transferred 载荷仅携 toBedId 床位 id 无床号，bed_no
 * 留旧值不落 id 文本（该列语义=床号文本）；床号由 bed.changed 事件补齐。乱序边界：
 * bed.changed 与 transferred 双向乱序终态一致（床号更新按 patientId 定位不按 ward 过滤）；
 * bed.changed(OCCUPIED) 与 admitted 同事务异队列发布、消费无顺序保证——占床帧先达时首更新
 * 0 行，短暂等待入科 upsert 落行后重试一次（仍 0 行 warn 留痕直返，宁缺勿错）。
 *
 * <p><b>大屏床位患者动态推送（Task 11 接线）</b>：投影四路写入成功后发布
 * {@link NurseBoardPushEvent}（type=BED_PATIENT）——admitted 推入科病区、transferred 推
 * 转出+转入双病区（源病区床位墙需移除行）、discharged 推出院病区（路由病区取逻辑删前
 * 投影行）、bed.changed 推床位病区（投影行回读携 visitId 定位键）。本监听器零事务注解
 * （MQ 消费线程），推送事件经 NurseBoardPushListener fallbackExecution 立即出站。
 *
 * <p>幂等双层：eventId 构件幂等（IdempotentConsumerSupport 三段式）+ 业务级幂等（重定向
 * fromWard 谓词 / 终清未执行态谓词 / 投影四路 CAS 谓词——重复投递 0 行自然达成；admitted
 * 双投递并发撞 uk_ward_patient_visit 捕获回查合并转条件更新）。载荷以 JsonNode 读（禁依赖
 * inpatient api——GC11 模块依赖单向红线）。终清与投影双写各自原子，无外层事务：部分成功由
 * 消费重试收敛（各谓词均幂等，重放零重复副作用）——与既有 nursing 监听器零事务注解形态一致
 * （PatientMergedListener 先例，@Transactional 会连带回滚三段式 FAILED 留痕登记）。
 * 载荷字段缺失/形态违约抛 ISE 进死信留痕。
 *
 * <p>归 internal/：容器驱动入口禁外引；Bean 注册点 NursingMessagingConfig @Import。
 */
@Slf4j
public class InpatientVisitEventListener {

    /** 出院终清撤销原因（固定文案，执行单/任务两路留痕同源） */
    static final String DISCHARGE_CLEANUP_REASON = "出院终清";

    /** 消费链路无登录上下文的操作者（与审计列默认同源） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 护理级别缺省值（V801 nursing_level 列默认同源；V800 id 48 载荷组件可空） */
    private static final String DEFAULT_NURSING_LEVEL = "NORMAL";

    /** 占床帧先于入科帧到达的乱序自愈等待窗（毫秒）：覆盖异队列消费竞序的常规窗口 */
    private static final long BED_RECONCILE_WAIT_MILLIS = 500L;

    private final IdempotentConsumerSupport consumerSupport;

    private final OrderExecutionMapper orderExecutionMapper;

    private final NursingTaskMapper nursingTaskMapper;

    private final NursingWardPatientMapper wardPatientMapper;

    /** 进程内事件发布器：大屏床位患者动态推送桥（事务内禁推送红线的进程内载体），非空 */
    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 NursingMessagingConfig @Import；消费模板 @Qualifier 定绑
     * nursingConsumerSupport——GC7 common 模板类多实例红线；单测按位置构造零改动）。
     *
     * @param consumerSupport      消费模板，非空；定绑 NursingMessagingConfig nursingConsumerSupport Bean
     * @param orderExecutionMapper 执行单 mapper，非空；重定向与终清撤销直驱条件更新（监听器薄切片，
     *                            与 PatientMergedListener 直驱 mapper 同款形态）
     * @param nursingTaskMapper    护理任务 mapper，非空；出院终清在途任务批量撤销写面
     * @param wardPatientMapper    病区患者投影 mapper，非空；投影写入四路（Task 7 实装写面）
     * @param events               进程内事件发布器，非空；大屏 BED_PATIENT 帧发布（Task 11 接线）
     */
    public InpatientVisitEventListener(
            @Qualifier("nursingConsumerSupport") IdempotentConsumerSupport consumerSupport,
            OrderExecutionMapper orderExecutionMapper,
            NursingTaskMapper nursingTaskMapper,
            NursingWardPatientMapper wardPatientMapper,
            ApplicationEventPublisher events) {
        this.consumerSupport = consumerSupport;
        this.orderExecutionMapper = orderExecutionMapper;
        this.nursingTaskMapper = nursingTaskMapper;
        this.wardPatientMapper = wardPatientMapper;
        this.events = events;
    }

    /**
     * 患者入科消费入口（q.nursing.inpatient.visit.admitted，V800 id 48）：投影 upsert，
     * 执行域无动作。
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
     * 更新 + 执行单重定向。
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
     * 执行单/在途任务批量 CANCELLED + 投影逻辑删）。
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
     * 床位动态变更消费入口（q.nursing.inpatient.bed.changed，V800 id 52）：投影 bed_no 补齐
     * （占床/转入且有患者主体时——床号文本唯一写入面）。
     *
     * @param message 原始消息帧，非空
     */
    @RabbitListener(
            queues = NursingMessagingConstants.QUEUE_PREFIX + NursingMessagingConstants.EVENT_SUB_INPATIENT_BED_CHANGED)
    public void onBedChanged(Message message) {
        consumerSupport.consume(message, this::handleBedChanged);
    }

    /**
     * 入科业务体（包级直驱可测）：投影 upsert——在册行（含 discharged 后重新入院的既有逻辑删
     * 行以外的行）刷新入科属性；无行插新行（bed_no/patient_name 落空占位，床号由 bed.changed
     * 补齐、展示名经详情嵌查）。并发双投递撞 uk_ward_patient_visit 捕获回查合并转条件更新
     * （D-23 同款两层兜底范式：DB 唯一约束为底 + 应用层冲突回查收敛）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 48 冻结
     * @throws IllegalStateException 缺 visitId/patientId/wardId/admittedAt（死信留痕）或冲突回查
     *                               仍无行（数据异常防御）时触发
     */
    void handleVisitAdmitted(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireText(envelope, payload, "visitId");
        long patientId = requirePatientId(envelope, payload);
        String wardId = requireText(envelope, payload, "wardId");
        upsertWardPatientProjection(
                visitId,
                patientId,
                wardId,
                payload.path("bedId").asLong(0),
                requireInstant(envelope, payload, "admittedAt"),
                textOrNull(payload, "nursingLevel"));
        // 消息发送：大屏床位动态帧（入科病区；bedNo 空占位待 bed.changed 补齐——载荷可空承载）
        publishBedPatientEvent(wardId, visitId, patientId, null);
    }

    /**
     * 转科/转床业务体（包级直驱可测）：投影归属更新（ward 切换、bed_no 留旧值——载荷无床号禁落
     * 床位 id 文本，W-34 裁决）+ 未执行执行单重定向（ward 切换、bed_no 随事件、计划时间不动
     * ——fromWard 谓词承载重复投递幂等）。
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
        // 数据库写操作：投影归属更新 CAS（fromWard 谓词幂等；bed_no 留旧值待 bed.changed 补齐）
        int projectionRows = wardPatientMapper.casTransferWard(visitId, fromWardId, toWardId, SYSTEM_OPERATOR);
        log.info(
                "转科投影归属更新：visitId={}，fromWardId={}，toWardId={}，行数={}（bed_no 留旧值，待 bed.changed 补齐床号文本）",
                visitId,
                fromWardId,
                toWardId,
                projectionRows);
        // 数据库写操作：执行单重定向 CAS（未执行三态 + fromWard 幂等谓词；计划时间不动）
        int rows = orderExecutionMapper.casRedirectWard(
                visitId, fromWardId, toWardId, String.valueOf(toBedId), SYSTEM_OPERATOR);
        log.info(
                "转科执行单重定向：visitId={}，fromWardId={}，toWardId={}，重定向行数={}（未执行态，计划时间不变）",
                visitId,
                fromWardId,
                toWardId,
                rows);
        // 消息发送：大屏床位动态帧双病区（转入侧新增行 + 转出侧移除行——两墙均需刷新；
        // 载荷 wardId 承载转入归属，转出侧同帧触发整墙刷新）
        publishBedPatientEvent(toWardId, visitId, patientId, null);
        publishBedPatientEvent(fromWardId, visitId, patientId, null);
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
     * 投影逻辑删（deleted=1——W-34 口径禁物理删，同 visitId 再入院可重新 upsert）。
     *
     * @param envelope 事件信封，非空；载荷契约 V800 id 51 冻结
     * @throws IllegalStateException 缺 visitId/patientId/dischargedAt（死信留痕）时触发
     */
    void handleVisitDischarged(EventEnvelope envelope) {
        JsonNode payload = envelope.payload();
        String visitId = requireText(envelope, payload, "visitId");
        long patientId = requirePatientId(envelope, payload);
        requireInstant(envelope, payload, "dischargedAt");
        // 数据库读操作：逻辑删前投影行回读（大屏推送路由病区锚——V800 id 51 载荷无 wardId；
        // 重复投递行已删回读 null，自然跳过推送与下方 CAS 同幂等口径）
        NursingWardPatient before = wardPatientMapper.selectOne(
                Wrappers.<NursingWardPatient>lambdaQuery().eq(NursingWardPatient::getVisitId, visitId));
        // 数据库写操作：未执行执行单终清 CAS（未执行三态谓词，0 行=已终清幂等达成）
        int executions = orderExecutionMapper.casCancelByVisit(visitId, DISCHARGE_CLEANUP_REASON, SYSTEM_OPERATOR);
        // 数据库写操作：在途任务终清 CAS（PENDING/IN_PROGRESS 谓词，与任务域 cancel 状态面同构）
        int tasks = nursingTaskMapper.casCancelByVisit(visitId, DISCHARGE_CLEANUP_REASON, SYSTEM_OPERATOR);
        // 数据库写操作：投影逻辑删 CAS（deleted=1；0 行=已删幂等）
        int projectionRows = wardPatientMapper.casDischarge(visitId, SYSTEM_OPERATOR);
        log.info(
                "出院终清：visitId={}，执行单撤销行数={}，任务撤销行数={}，投影逻辑删行数={}（EXECUTING 执行单不动，归输注中断特殊面）",
                visitId,
                executions,
                tasks,
                projectionRows);
        // 消息发送：大屏床位动态帧（首删命中才推——出院病区墙移除行；路由病区=逻辑删前投影行归属）
        if (before != null && projectionRows > 0) {
            publishBedPatientEvent(before.getWardId(), visitId, patientId, before.getBedNo());
        }
    }

    /**
     * 床位变更业务体（包级直驱可测）：占床/转入且有患者主体（patientId 非空）时补齐投影床号文本
     * （床号唯一写入面）；预占/释放/消毒/维修等无主体场景直返（投影不更新）。执行域无动作。
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
     * 投影 upsert 写入面（入科路，Task 7 实装）：在册行刷新入科属性；无行插新行——
     * bed_no 落空占位（V800 id 48 载荷仅携床位 id 无床号，床号语义列禁落 id 文本，待
     * bed.changed 补齐）、patient_name 落空占位（事件载荷脱敏红线不携姓名，详情卡经
     * patient api 嵌查）。并发双投递撞 uk_ward_patient_visit（在册唯一）捕获后回查合并转
     * 条件更新（回查仍无行定性数据异常防御，死信留痕）。
     *
     * @param visitId      住院就诊号，非空；来源：事件载荷
     * @param patientId    患者主索引，非空；来源：事件载荷
     * @param wardId       入科病区编码，非空；来源：事件载荷
     * @param bedId        入科床位 id，非空（床号补齐归 bed.changed，本面不消费）；来源：事件载荷
     * @param admittedAt   入科确认时点，非空；来源：事件载荷
     * @param nursingLevel 护理级别 code，可空（缺省 NORMAL 与 V801 列默认同源）；来源：事件载荷
     */
    private void upsertWardPatientProjection(
            String visitId, long patientId, String wardId, long bedId, Instant admittedAt, String nursingLevel) {
        String level = nursingLevel == null ? DEFAULT_NURSING_LEVEL : nursingLevel;
        OffsetDateTime admittedAtDb = OffsetDateTime.ofInstant(admittedAt, ZoneOffset.UTC);
        NursingWardPatient existing = wardPatientMapper.selectOne(
                Wrappers.<NursingWardPatient>lambdaQuery().eq(NursingWardPatient::getVisitId, visitId));
        if (existing != null) {
            // 幂等重放：在册行仅刷新入科属性（visit_id + deleted=0 谓词；同值覆盖零语义副作用）
            wardPatientMapper.casAdmitRefresh(visitId, wardId, level, admittedAtDb, SYSTEM_OPERATOR);
            log.info(
                    "入科投影刷新（在册行）：visitId={}，patientId={}，wardId={}，nursingLevel={}", visitId, patientId, wardId, level);
            return;
        }
        NursingWardPatient row = new NursingWardPatient();
        row.setWardId(wardId);
        row.setBedNo("");
        row.setPatientId(patientId);
        row.setVisitId(visitId);
        row.setPatientName("");
        row.setNursingLevel(level);
        row.setAllergyFlag(false);
        row.setRiskFlags("");
        row.setAdmittedAt(admittedAtDb);
        row.setCreatedBy(SYSTEM_OPERATOR);
        row.setUpdatedBy(SYSTEM_OPERATOR);
        try {
            // 数据库写操作：投影新行落库（uk_ward_patient_visit/uk_ward_patient_bed 双唯一兜底并发）
            wardPatientMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发双投递回查合并（D-23 同款范式）：对方投递已落行 → 转条件更新收敛；
            // 回查仍无行=冲突键非本 visit（如同病区双空占位床号竞态），定性数据异常防御死信留痕
            NursingWardPatient concurrent = wardPatientMapper.selectOne(
                    Wrappers.<NursingWardPatient>lambdaQuery().eq(NursingWardPatient::getVisitId, visitId));
            if (concurrent == null) {
                throw new IllegalStateException("入科投影唯一冲突回查无行（数据异常防御）：visitId=" + visitId + "，wardId=" + wardId, e);
            }
            wardPatientMapper.casAdmitRefresh(visitId, wardId, level, admittedAtDb, SYSTEM_OPERATOR);
            log.info("入科投影冲突回查合并（并发双投递收敛为条件更新）：visitId={}，wardId={}，nursingLevel={}", visitId, wardId, level);
            return;
        }
        log.info(
                "入科投影写入：visitId={}，patientId={}，wardId={}，bedId={}，nursingLevel={}（bed_no 空占位待 bed.changed 补齐）",
                visitId,
                patientId,
                wardId,
                bedId,
                level);
    }

    /**
     * 投影床号补齐写入面（床位变更路，Task 7 实装）：按占用患者主索引定位在册投影行更新床号文本
     * （不按 ward 过滤——bed.changed 先于 transferred 到达时旧病区行先补新床号、transferred
     * 后到切病区即收敛）。乱序自愈：首更新 0 行（占床帧先于入科帧到达、投影行未落）时等待
     * {@link #BED_RECONCILE_WAIT_MILLIS} 后重试一次；仍 0 行 warn 留痕直返（宁缺勿错，
     * 不抛出无限重试——patientId 无在册投影行属数据异常，交人工核查）。
     *
     * @param wardId    床位归属病区编码，非空；来源：事件载荷
     * @param bedId     床位 id，非空（日志留痕面）；来源：事件载荷
     * @param bedNo     床号文本，非空；来源：事件载荷
     * @param bedStatus 迁移后床位状态 code，可空；来源：事件载荷
     * @param patientId 占用患者主索引，非空（无主体已在调用前直返）；来源：事件载荷
     */
    private void updateProjectionBedNo(String wardId, long bedId, String bedNo, String bedStatus, long patientId) {
        // 数据库写操作：床号补齐 CAS（patientId 定位 + IS DISTINCT FROM 重放幂等谓词）
        int rows = wardPatientMapper.casUpdateBedNoByPatient(patientId, bedNo, SYSTEM_OPERATOR);
        if (rows == 0) {
            // 异步等待（乱序自愈窗口）：admitted 同事务异队列发布，消费无顺序保证——占床帧先达时
            // 投影行尚未落库，短暂等待入科 upsert 落行后重试一次；中断位恢复保序不吞信号
            try {
                Thread.sleep(BED_RECONCILE_WAIT_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            rows = wardPatientMapper.casUpdateBedNoByPatient(patientId, bedNo, SYSTEM_OPERATOR);
            if (rows == 0) {
                log.warn(
                        "床位变更补床号未命中在册投影行（乱序自愈重试后仍 0 行，宁缺勿错留痕）：wardId={}，bedId={}，bedNo={}，bedStatus={}，patientId={}",
                        wardId,
                        bedId,
                        bedNo,
                        bedStatus,
                        patientId);
                return;
            }
        }
        log.info(
                "床位变更投影床号补齐：wardId={}，bedId={}，bedNo={}，bedStatus={}，patientId={}",
                wardId,
                bedId,
                bedNo,
                bedStatus,
                patientId);
        // 消息发送：大屏床位动态帧（床号补齐成功路径；投影行回读携 visitId 定位键——理论上
        // 必命中（CAS 刚按 patientId 命中在册行），回读零行属并发逻辑删竞态，跳过推送留痕）
        NursingWardPatient row = wardPatientMapper.selectOne(
                Wrappers.<NursingWardPatient>lambdaQuery().eq(NursingWardPatient::getPatientId, patientId));
        if (row != null) {
            publishBedPatientEvent(wardId, row.getVisitId(), patientId, bedNo);
        } else {
            log.warn("床位变更大屏帧跳过（投影行回读零行——并发逻辑删竞态留痕）：patientId={}，bedNo={}", patientId, bedNo);
        }
    }

    /**
     * 大屏床位患者动态帧发布单点（四路共用，Task 11 接线）：type=BED_PATIENT，本监听器零事务
     * 上下文（MQ 消费线程），NurseBoardPushListener 经 fallbackExecution 立即出站。
     *
     * @param wardId    路由病区（topic 尾段），非空
     * @param visitId   住院就诊号（行定位键），非空
     * @param patientId 患者主索引，非空
     * @param bedNo     变更后床号文本（admitted/transferred 路未知为 null——床号待 bed.changed 补齐），可空
     */
    private void publishBedPatientEvent(String wardId, String visitId, long patientId, String bedNo) {
        events.publishEvent(new NurseBoardPushEvent(
                wardId,
                NurseBoardPushFrame.TYPE_BED_PATIENT,
                new NurseBoardPushFrame.BedPatientPayload(visitId, patientId, bedNo, wardId),
                Instant.now()));
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
