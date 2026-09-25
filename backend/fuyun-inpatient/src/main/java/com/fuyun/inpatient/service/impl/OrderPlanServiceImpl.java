package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderExecutedPayload;
import com.fuyun.inpatient.api.payload.OrderPlanGeneratedPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.ExecuteConfirmRequest;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import com.fuyun.inpatient.entity.OrderAudit;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import com.fuyun.inpatient.entity.OrderFrequency;
import com.fuyun.inpatient.entity.OrderStatusLog;
import com.fuyun.inpatient.entity.OrderTransferLog;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.PlanStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderAuditMapper;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import com.fuyun.inpatient.mapper.OrderFrequencyMapper;
import com.fuyun.inpatient.mapper.OrderStatusLogMapper;
import com.fuyun.inpatient.mapper.OrderTransferLogMapper;
import com.fuyun.inpatient.service.OrderPlanService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.ExecuteConfirmVO;
import com.fuyun.inpatient.vo.OrderTraceVO;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 医嘱执行计划服务实现（FU-M04-06 下，Task 8 业务面）。四能力面：
 *
 * <p>① 日切批量分解（decomposeNextDay）：全院在院（ADMITTED）就诊下 TRANSFERRED/EXECUTING
 * 长期医嘱 × 频次时点序列 → 目标日计划行（明细行×时点粒度）。分批提交（每 500 医嘱一事务，
 * 可断点续跑）经 TransactionTemplate 编程式事务承载——同类内分批循环无法走 @Transactional
 * 代理（自调用失效），编程式事务是分批事务边界的标准解法；查前置（医嘱+目标日已有行跳过）
 * 与 uk_plan_order_item_time 唯一约束双幂等兜底重跑。分解失败（频次字典缺失等）不建异常
 * 清单表——warn 日志（order_no+缺失原因，供次日夜内缓冲人工处理）+ 该医嘱不生成（简化
 * 决策，偏差登记 Task 16）。prn（按需嘱托）与无固定时点频次（st 临时即刻——临时单次计划
 * 已在转抄链生成）不经日切面：计划仅由时点序列驱动（Spec §3.2 选定方案口径）。
 *
 * <p>② 当日增量补偿（compensateToday）：长期医嘱审核+转抄后即时补生成当日剩余时点（now
 * 之后）计划——OrderTransferServiceImpl.transferCheck 长期医嘱分支调用（转抄事务内加入，
 * 与 Task 7 临时单次计划生成点互斥勿双头生成）。
 *
 * <p>③ 执行回签（executeConfirm，CF-6/W-33 契约实装）：计划 PENDING→EXECUTED CAS（0 行=
 * 已 EXECUTED 幂等返回当前状态、不迁移不发事件；CANCELLED 拒 IP-1015）+ 医嘱头三态推进
 * （唯一经状态机）+ 事务内发布 inpatient.order.executed（V800 id 47 载荷）。
 *
 * <p>④ 闭环追溯（trace）：order_status_log + order_audit + order_transfer_log +
 * order_execute_plan 四源时间线按发生时点升序稳定排序聚合（人/时/果一屏）。
 *
 * <p>线程安全：无状态 singleton；executeConfirm/compensateToday 注解事务收口，
 * decomposeNextDay 分批编程式事务；事件一律事务内 publishEvent → AFTER_COMMIT 出 MQ（GC8）。
 */
@Slf4j
public class OrderPlanServiceImpl implements OrderPlanService {

    /** 日切分批事务的批量上界（brief 冻结：每 500 医嘱一事务，可断点续跑） */
    private static final int DECOMPOSE_BATCH_SIZE = 500;

    /** 系统操作者（日切任务无操作者上下文——审计列回退口径，与 cancelFuturePlans 同款） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 状态机留痕原因：长期医嘱首个执行回签（TRANSFERRED→EXECUTING） */
    private static final String REASON_FIRST_CONFIRM = "长期医嘱首个执行回签";

    /** 状态机留痕原因：临时医嘱单次执行回签（TRANSFERRED→COMPLETED） */
    private static final String REASON_STAT_CONFIRM = "临时医嘱单次执行回签";

    /** 状态机留痕原因：全部执行计划实例终态（EXECUTING→COMPLETED） */
    private static final String REASON_ALL_PLANS_TERMINAL = "全部执行计划实例终态";

    /** 追溯环节 code：开立（医嘱头承载） */
    private static final String STAGE_ORDERED = "ORDERED";

    /** 追溯环节 code：审核（order_audit——SYSTEM/PHARMACIST 双轨） */
    private static final String STAGE_AUDIT = "AUDIT";

    /** 追溯环节 code：转抄核对（order_transfer_log） */
    private static final String STAGE_TRANSFER = "TRANSFER";

    /** 追溯环节 code：计划执行（order_execute_plan） */
    private static final String STAGE_PLAN = "PLAN";

    /** 追溯环节 code：状态迁移（order_status_log——含停止） */
    private static final String STAGE_STATUS = "STATUS";

    /** 时点序列格式（V904 time_points 列契约：HH:mm 逗号分隔） */
    private static final DateTimeFormatter TIME_POINT_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final MedicalOrderMapper orderMapper;

    private final MedicalOrderItemMapper itemMapper;

    private final OrderExecutePlanMapper planMapper;

    private final OrderFrequencyMapper frequencyMapper;

    private final OrderAuditMapper auditMapper;

    private final OrderStatusLogMapper statusLogMapper;

    private final OrderTransferLogMapper transferLogMapper;

    private final InpatientVisitMapper visitMapper;

    private final InpatientSeqGate seqGate;

    private final OrderStateMachineService stateMachine;

    private final ApplicationEventPublisher events;

    private final TransactionTemplate transactionTemplate;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param orderMapper         医嘱主表 mapper，非空；候选查询与回签关联定位
     * @param itemMapper          医嘱明细 mapper，非空；计划按明细行粒度生成取数
     * @param planMapper          执行计划 mapper，非空；计划开立/查前置/回签 CAS/追溯
     * @param frequencyMapper     频次字典 mapper，非空；时点序列解析权威取数源
     * @param auditMapper         审核流水 mapper，非空；追溯审核环节聚合
     * @param statusLogMapper     状态迁移日志 mapper，非空；追溯状态环节聚合
     * @param transferLogMapper   转抄台账 mapper，非空；追溯转抄环节聚合
     * @param visitMapper         住院就诊 mapper，非空；在院候选与病区定位
     * @param seqGate             住院业务号发号器（PL 计划号），非空
     * @param stateMachine        医嘱状态机服务（医嘱头迁移唯一执行面），非空
     * @param events              进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     * @param transactionTemplate 编程式事务模板（Boot 自动装配单例）——日切分批事务边界，非空
     */
    public OrderPlanServiceImpl(
            MedicalOrderMapper orderMapper,
            MedicalOrderItemMapper itemMapper,
            OrderExecutePlanMapper planMapper,
            OrderFrequencyMapper frequencyMapper,
            OrderAuditMapper auditMapper,
            OrderStatusLogMapper statusLogMapper,
            OrderTransferLogMapper transferLogMapper,
            InpatientVisitMapper visitMapper,
            InpatientSeqGate seqGate,
            OrderStateMachineService stateMachine,
            ApplicationEventPublisher events,
            TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.planMapper = planMapper;
        this.frequencyMapper = frequencyMapper;
        this.auditMapper = auditMapper;
        this.statusLogMapper = statusLogMapper;
        this.transferLogMapper = transferLogMapper;
        this.visitMapper = visitMapper;
        this.seqGate = seqGate;
        this.stateMachine = stateMachine;
        this.events = events;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 日切批量分解全量入口：候选查询 → 500 医嘱分批编程式事务逐批提交。单批业务冲突
     * （并发唯一冲突等）仅回滚该批并续跑下一批——已提交批次由查前置幂等跳过（断点续跑）。
     */
    @Override
    public int decomposeNextDay(LocalDate planDate) {
        List<MedicalOrder> candidates = decomposeCandidates();
        if (candidates.isEmpty()) {
            log.info("日切分解无候选长期医嘱（全院无在院 TRANSFERRED/EXECUTING 长期医嘱）：planDate={}", planDate);
            return 0;
        }
        int created = 0;
        for (List<MedicalOrder> chunk : partition(candidates)) {
            try {
                // 分批事务边界（每 500 医嘱一事务）：批内任一医嘱的数据库写与事件发布同批成败与共
                Integer batchCreated = transactionTemplate.execute(status -> decomposeBatch(chunk, planDate));
                created += batchCreated == null ? 0 : batchCreated;
            } catch (BizException e) {
                // 并发窗口唯一冲突等业务冲突：该批回滚续跑（重跑由查前置+唯一约束双幂等兜底）；
                // 批内医嘱号截断预览（≤10）——完整批清单以 planDate+批序可由候选查询复现，禁整批刷屏
                log.error(
                        "日切分解批次失败（该批回滚，续跑下一批）：planDate={}，批内医嘱 {} 条（预览 {}），原因={}",
                        planDate,
                        chunk.size(),
                        chunk.stream().limit(10).map(MedicalOrder::getOrderNo).toList(),
                        e.getMessage(),
                        e);
            }
        }
        log.info("日切分解完成：planDate={}，候选长期医嘱 {} 条，生成计划 {} 行", planDate, candidates.size(), created);
        return created;
    }

    /** 当日增量补偿入口（服务器时钟形态）：见 {@link #compensateToday(String, OffsetDateTime)}。 */
    @Override
    @Transactional
    public int compensateToday(String orderNo) {
        return compensateToday(orderNo, OffsetDateTime.now());
    }

    /**
     * 当日增量补偿（固定时钟注入形态，包级可见供单测直测补偿时点过滤语义）：长期医嘱剩余
     * 时点（now 之后）即时补生成；频次字典缺失等异常 warn 留痕不阻断调用方事务（转抄主链
     * 不因补偿面回滚）。非长期医嘱与未经转抄医嘱直过（临时单次计划由 Task 7 转抄链生成，
     * 勿双头生成）。
     *
     * @param orderNo 医嘱号，非空
     * @param now     补偿基准时点（仅生成该时点之后的当日时点），非空
     * @return 新生成计划行数（无剩余时点/跳过面为 0），非负
     */
    int compensateToday(String orderNo, OffsetDateTime now) {
        MedicalOrder order = requireOrder(orderNo);
        // 补偿面仅长期医嘱（临时医嘱转抄同步生成单次计划——Task 7 既有生成点）
        if (!OrderClass.LONG.getCode().equals(order.getOrderClass())) {
            log.info("当日补偿跳过（非长期医嘱——临时单次计划由转抄链同步生成）：orderNo={}，orderClass={}", orderNo, order.getOrderClass());
            return 0;
        }
        // 补偿面限转抄后状态（AUDITED 前置未完成/终态医嘱无计划面）
        if (!OrderStatus.TRANSFERRED.getCode().equals(order.getStatus())
                && !OrderStatus.EXECUTING.getCode().equals(order.getStatus())) {
            log.info("当日补偿跳过（医嘱未经转抄或已终态）：orderNo={}，status={}", orderNo, order.getStatus());
            return 0;
        }
        String operator = operatorOrSystem();
        int created = generatePlans(order, now.toLocalDate(), now, operator);
        log.info("当日增量补偿完成：orderNo={}，剩余时点生成计划 {} 行，operator={}", orderNo, created, operator);
        return created;
    }

    /**
     * 执行回签（CF-6/W-33 契约实装，单事务）：计划 CAS → 幂等裁决 → 医嘱头三态推进 →
     * executed 事件 → 出参。
     */
    @Override
    @Transactional
    public ExecuteConfirmVO executeConfirm(String planNo, ExecuteConfirmRequest req) {
        OrderExecutePlan plan = requirePlan(planNo);
        long operator = parseOperatorAsEmployeeId();
        // 执行时点缺省服务器时间（W-33 契约：executedAt 可空缺省服务器时间）
        OffsetDateTime executedAt = req.executedAt() == null ? OffsetDateTime.now() : req.executedAt();
        // 数据库写操作：计划行状态 CAS（PENDING 限定；0 行=非 PENDING 态——幂等/拒绝裁决面）
        int rows = planMapper.casExecuteConfirm(
                plan.getPlanNo(),
                String.valueOf(req.executorId()),
                executedAt,
                req.routeCheckResult(),
                String.valueOf(operator));
        if (rows == 0) {
            // CAS 零行重读实态（并发窗口他方可能刚回签）——已 EXECUTED 幂等返回当前状态
            OrderExecutePlan current = requirePlan(planNo);
            if (PlanStatus.EXECUTED.getCode().equals(current.getStatus())) {
                MedicalOrder order = requireOrderById(plan.getOrderId());
                log.info(
                        "重复回签幂等返回当前状态（不迁移不发事件）：planNo={}，orderNo={}，orderStatus={}，executorId={}，operator={}",
                        planNo,
                        order.getOrderNo(),
                        order.getStatus(),
                        req.executorId(),
                        operator);
                return new ExecuteConfirmVO(
                        planNo, order.getOrderNo(), order.getStatus(), PlanStatus.EXECUTED.getCode());
            }
            // CANCELLED 终态（或词表外脏数据）：回签面拒绝——fail-closed
            log.warn(
                    "执行回签被拒（计划状态不允许）：planNo={}，status={}，executorId={}，operator={}",
                    planNo,
                    current.getStatus(),
                    req.executorId(),
                    operator);
            throw new BizException(
                    InpatientErrorCode.PLAN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "执行计划状态不允许回签（仅 PENDING 待执行态）：planNo=" + planNo + "，当前状态=" + current.getStatus());
        }
        MedicalOrder order = requireOrderById(plan.getOrderId());
        InpatientVisit visit = requireVisitById(order.getVisitId());
        // 医嘱头三态推进（唯一经状态机——留痕随状态机自动落 order_status_log）
        advanceOrderHead(order, operator);
        // 事务内发布执行回签事件（AFTER_COMMIT 出 fy.topic；M13 据此确认住院费用——V800 id 47
        // 载荷逐字：m04OrderNo/visitId/patientId/planNo/executedAt；禁患者姓名/诊断文本）
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_ORDER_EXECUTED,
                new OrderExecutedPayload(
                        order.getOrderNo(),
                        visit.getVisitId(),
                        order.getPatientId(),
                        plan.getPlanNo(),
                        executedAt.toInstant())));
        log.info(
                "执行回签完成：planNo={}，orderNo={}，orderStatus={}，executorId={}，executedAt={}，operator={}",
                planNo,
                order.getOrderNo(),
                order.getStatus(),
                req.executorId(),
                executedAt,
                operator);
        return new ExecuteConfirmVO(planNo, order.getOrderNo(), order.getStatus(), PlanStatus.EXECUTED.getCode());
    }

    /** 闭环追溯聚合：四源时间线（开立/审核/转抄/计划执行/状态迁移）按发生时点升序稳定排序。 */
    @Override
    @Transactional(readOnly = true)
    public OrderTraceVO trace(String orderNo) {
        MedicalOrder order = requireOrder(orderNo);
        InpatientVisit visit = requireVisitById(order.getVisitId());
        List<OrderTraceVO.TraceEntry> entries = new ArrayList<>();
        // 环节一·开立：医嘱头承载（开立医生/开立时点；detail 携类型/分类/频次定位面）
        entries.add(new OrderTraceVO.TraceEntry(
                STAGE_ORDERED,
                order.getDoctorId(),
                order.getOrderedAt(),
                OrderStatus.CREATED.getCode(),
                order.getOrderType() + "/" + order.getOrderClass()
                        + (isBlank(order.getFreqCode()) ? "" : "/" + order.getFreqCode())));
        // 环节二·审核（含药师）：order_audit 双轨（SYSTEM 预检/PHARMACIST 审方）
        auditMapper
                .selectList(Wrappers.<OrderAudit>lambdaQuery().eq(OrderAudit::getOrderId, order.getId()))
                .forEach(audit -> entries.add(new OrderTraceVO.TraceEntry(
                        STAGE_AUDIT,
                        audit.getAuditOperator(),
                        audit.getOccurredAt(),
                        audit.getConclusion(),
                        audit.getReason())));
        // 环节三·转抄核对：order_transfer_log（护士/时点/结论/第二核对人）
        transferLogMapper
                .selectList(Wrappers.<OrderTransferLog>lambdaQuery().eq(OrderTransferLog::getOrderId, order.getId()))
                .forEach(row -> entries.add(new OrderTraceVO.TraceEntry(
                        STAGE_TRANSFER,
                        row.getTransferNurse(),
                        row.getTransferredAt(),
                        row.getConclusion(),
                        isBlank(row.getSecondCheckerId()) ? null : "第二核对人=" + row.getSecondCheckerId())));
        // 环节四·各计划执行：order_execute_plan（未执行行以计划时点占位——人/时/果三缺一可辨）
        planMapper
                .selectList(Wrappers.<OrderExecutePlan>lambdaQuery().eq(OrderExecutePlan::getOrderId, order.getId()))
                .forEach(plan -> entries.add(new OrderTraceVO.TraceEntry(
                        STAGE_PLAN,
                        plan.getExecutorId(),
                        plan.getExecutedAt() == null ? plan.getPlanTime() : plan.getExecutedAt(),
                        plan.getStatus(),
                        plan.getPlanNo() + "@"
                                + plan.getPlanTime().toLocalTime().format(TIME_POINT_FORMAT))));
        // 环节五·状态迁移（含停止）：order_status_log（from→to+原因）
        statusLogMapper
                .selectList(Wrappers.<OrderStatusLog>lambdaQuery().eq(OrderStatusLog::getOrderId, order.getId()))
                .forEach(row -> entries.add(new OrderTraceVO.TraceEntry(
                        STAGE_STATUS,
                        row.getOperator(),
                        row.getOccurredAt(),
                        row.getToStatus(),
                        row.getFromStatus() + "→" + row.getToStatus()
                                + (isBlank(row.getReason()) ? "" : "，" + row.getReason()))));
        // 发生时点升序稳定排序（List.sort 稳定——同时点保持采集序：开立→审核→转抄→计划→状态）
        entries.sort(Comparator.comparing(
                OrderTraceVO.TraceEntry::occurredAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new OrderTraceVO(
                orderNo, visit.getVisitId(), order.getOrderType(), order.getOrderClass(), order.getStatus(), entries);
    }

    /**
     * 日切候选长期医嘱查询：全院在院（ADMITTED）就诊 × TRANSFERRED/EXECUTING × LONG
     * （brief 冻结候选面；COMPLETED/STOPPED/CANCELLED 终态不再分解）。
     *
     * @return 候选医嘱行全集（未分批），非空（空集=无候选）
     */
    private List<MedicalOrder> decomposeCandidates() {
        // 数据库读操作：在院就诊全集（ADMITTED——出院申请中患者的在途医嘱由出院清理面收口）
        List<InpatientVisit> visits = visitMapper.selectList(
                Wrappers.<InpatientVisit>lambdaQuery().eq(InpatientVisit::getStatus, VisitStatus.ADMITTED.getCode()));
        if (visits.isEmpty()) {
            return List.of();
        }
        // 数据库读操作：在院就诊下转抄后长期医嘱全集（频率面广时 IN 集约千级，单查询承载）
        return orderMapper.selectList(Wrappers.<MedicalOrder>lambdaQuery()
                .in(
                        MedicalOrder::getVisitId,
                        visits.stream().map(InpatientVisit::getId).toList())
                .in(MedicalOrder::getStatus, OrderStatus.TRANSFERRED.getCode(), OrderStatus.EXECUTING.getCode())
                .eq(MedicalOrder::getOrderClass, OrderClass.LONG.getCode()));
    }

    /**
     * 分批事务单元（批内逐医嘱生成；字典类失败逐单 warn 跳过不抛——数据库写失败/唯一冲突
     * 传播至批边界回滚整批，续跑面由调用方承载）。
     *
     * @param chunk    本批医嘱集（≤500），非空
     * @param planDate 计划日期，非空
     * @return 本批新生成计划行数，非负
     */
    private int decomposeBatch(List<MedicalOrder> chunk, LocalDate planDate) {
        int created = 0;
        for (MedicalOrder order : chunk) {
            created += generatePlans(order, planDate, null, SYSTEM_OPERATOR);
        }
        return created;
    }

    /**
     * 计划生成共用实现（日切/补偿共用）：频次时点序列 → 明细行×时点计划行开立（查前置幂等）
     * + 按医嘱发布 order-plan.generated 事件。
     *
     * @param order    目标长期医嘱行，非空
     * @param planDate 计划日期（日切=次日/补偿=当日），非空
     * @param notBefore 有效时点下界（可空=不过滤——日切全量；补偿=基准时点，仅生成其后时点），可空
     * @param operator 审计操作者（日切=system/补偿=操作者上下文），非空
     * @return 新生成计划行数（跳过面为 0），非负
     */
    private int generatePlans(MedicalOrder order, LocalDate planDate, OffsetDateTime notBefore, String operator) {
        // 频次裁决（缺失/prn/无固定时点——留痕跳过，该医嘱不生成）
        OrderFrequency freq = decomposableFrequency(order);
        if (freq == null) {
            return 0;
        }
        List<LocalTime> points = parseTimePoints(order, freq);
        if (points.isEmpty()) {
            return 0;
        }
        // 数据库读操作：医嘱明细行全集（计划按明细行粒度开立）
        List<MedicalOrderItem> items = itemMapper.selectList(
                Wrappers.<MedicalOrderItem>lambdaQuery().eq(MedicalOrderItem::getOrderId, order.getId()));
        if (items.isEmpty()) {
            log.warn("计划生成跳过（医嘱明细缺失——计划行无生成粒度）：orderNo={}，原因=medical_order_item 无行", order.getOrderNo());
            return 0;
        }
        // 数据库读操作：关联就诊行（计划病区定位——生成时点患者所在病区）
        InpatientVisit visit = visitMapper.selectById(order.getVisitId());
        if (visit == null) {
            log.warn(
                    "计划生成跳过（关联就诊缺失——计划病区无法定位）：orderNo={}，原因=inpatient_visit 主键 {} 无命中",
                    order.getOrderNo(),
                    order.getVisitId());
            return 0;
        }
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        // 有效时点过滤：补偿面仅保留基准时点之后的当日时点；日切面全量
        List<LocalTime> effectivePoints = points.stream()
                .filter(point -> notBefore == null
                        || planDate.atTime(point).atOffset(offset).isAfter(notBefore))
                .toList();
        if (effectivePoints.isEmpty()) {
            log.info("计划生成跳过（当日无剩余时点）：orderNo={}，planDate={}", order.getOrderNo(), planDate);
            return 0;
        }
        // 查前置幂等：该医嘱目标日已有计划行（断点续跑/重复日切零新行——uk_plan_order_item_time 双保险）
        OffsetDateTime windowStart = planDate.atStartOfDay().atOffset(offset);
        OffsetDateTime windowEnd = planDate.plusDays(1).atStartOfDay().atOffset(offset);
        Set<String> existingKeys = planMapper
                .selectList(Wrappers.<OrderExecutePlan>lambdaQuery()
                        .eq(OrderExecutePlan::getOrderId, order.getId())
                        .ge(OrderExecutePlan::getPlanTime, windowStart)
                        .lt(OrderExecutePlan::getPlanTime, windowEnd))
                .stream()
                .map(plan -> plan.getOrderItemId() + "@" + plan.getPlanTime().toLocalTime())
                .collect(Collectors.toSet());
        List<OrderExecutePlan> created = new ArrayList<>();
        for (MedicalOrderItem item : items) {
            for (LocalTime point : effectivePoints) {
                if (existingKeys.contains(item.getId() + "@" + point)) {
                    continue;
                }
                OrderExecutePlan plan = buildPlan(order, item, visit, planDate, point, offset, operator);
                insertPlan(order, plan);
                created.add(plan);
            }
        }
        if (created.isEmpty()) {
            return 0;
        }
        // 事务内发布计划拆分事件（日切/补偿共用面；AFTER_COMMIT 出 fy.topic——V800 id 43 载荷逐字：
        // m04OrderNo/visitId/patientId/planDate/planNos[]/planTimes[]；planNos 与 planTimes 下标对齐）
        publishGenerated(order, visit, planDate, created);
        return created.size();
    }

    /**
     * 可分解频次裁决：缺失编码/字典无命中 warn 跳过（夜内缓冲人工处理面）；prn 按需频次与
     * 无固定时点频次（st 临时即刻等）info 跳过——计划仅由时点序列驱动（Spec §3.2）。
     *
     * @param order 目标医嘱行，非空
     * @return 可分解频次行；跳过面返回 null
     */
    private OrderFrequency decomposableFrequency(MedicalOrder order) {
        if (isBlank(order.getFreqCode())) {
            log.warn("计划生成跳过（频次编码缺失）：orderNo={}，原因=长期医嘱 freq_code 为空", order.getOrderNo());
            return null;
        }
        // 数据库读操作：频次字典定位（时点序列解析权威取数源）
        OrderFrequency freq = frequencyMapper.selectOne(
                Wrappers.<OrderFrequency>lambdaQuery().eq(OrderFrequency::getFreqCode, order.getFreqCode()));
        if (freq == null) {
            log.warn(
                    "计划生成跳过（频次字典缺失——次日夜内缓冲人工处理）：orderNo={}，freqCode={}，原因=order_frequency 无命中",
                    order.getOrderNo(),
                    order.getFreqCode());
            return null;
        }
        // prn 按需频次：不经计划拆分（嘱托触发面承载——Spec §3.2「嘱托不预生成」）
        if (Boolean.TRUE.equals(freq.getPrnFlag())) {
            log.info("计划生成跳过（prn 按需频次——嘱托触发面承载）：orderNo={}，freqCode={}", order.getOrderNo(), order.getFreqCode());
            return null;
        }
        // 无固定时点频次（st 临时即刻等）：计划仅由时点序列驱动，日切面留痕跳过
        if (isBlank(freq.getTimePoints())) {
            log.info("计划生成跳过（频次无固定时点序列——计划仅由时点序列驱动）：orderNo={}，freqCode={}", order.getOrderNo(), order.getFreqCode());
            return null;
        }
        return freq;
    }

    /**
     * 频次时点序列解析（V904 契约：HH:mm 逗号分隔）。
     *
     * @param order 目标医嘱行（warn 留痕定位键），非空
     * @param freq  频次行，非空
     * @return 时点集（解析失败返回空集——warn 留痕），非空
     */
    private List<LocalTime> parseTimePoints(MedicalOrder order, OrderFrequency freq) {
        String[] segments = freq.getTimePoints().split(",");
        List<LocalTime> points = new ArrayList<>(segments.length);
        try {
            for (String segment : segments) {
                points.add(LocalTime.parse(segment.trim()));
            }
        } catch (DateTimeParseException e) {
            log.warn(
                    "计划生成跳过（时点序列解析失败）：orderNo={}，freqCode={}，timePoints={}",
                    order.getOrderNo(),
                    order.getFreqCode(),
                    freq.getTimePoints());
            return List.of();
        }
        return points;
    }

    /**
     * 计划行构造（明细行×时点粒度）：plan_no=PL 流水、shift=计划时点落班（V801 窗口共用口径）。
     *
     * @param order    所属医嘱行，非空
     * @param item     医嘱明细行，非空
     * @param visit    关联就诊行（病区/就诊主键取数面），非空
     * @param planDate 计划日期，非空
     * @param point    计划时点，非空
     * @param offset   服务器时区偏移（日期+时点→OffsetDateTime 组合基准），非空
     * @param operator 审计操作者，非空
     * @return 待落库计划行（未落库），非空
     */
    private OrderExecutePlan buildPlan(
            MedicalOrder order,
            MedicalOrderItem item,
            InpatientVisit visit,
            LocalDate planDate,
            LocalTime point,
            ZoneOffset offset,
            String operator) {
        OrderExecutePlan plan = new OrderExecutePlan();
        plan.setPlanNo(seqGate.nextNo("PL"));
        plan.setOrderId(order.getId());
        plan.setOrderItemId(item.getId());
        plan.setVisitId(visit.getId());
        plan.setWardId(visit.getCurrentWardId());
        OffsetDateTime planTime = planDate.atTime(point).atOffset(offset);
        plan.setPlanTime(planTime);
        plan.setShift(OrderTransferServiceImpl.currentShift(planTime));
        plan.setStatus(PlanStatus.PENDING.getCode());
        plan.setCreatedBy(operator);
        plan.setUpdatedBy(operator);
        return plan;
    }

    /**
     * 计划行落库（uk_order_execute_plan_no/uk_plan_order_item_time 双唯一约束兜底并发窗口）。
     *
     * @param order 所属医嘱行（冲突文案定位键），非空
     * @param plan  待落库计划行，非空
     * @throws BizException IP-1023 并发窗口同项同时点重复生成（幂等拒绝——Task 7 同款定性）
     */
    private void insertPlan(MedicalOrder order, OrderExecutePlan plan) {
        try {
            // 数据库写操作：计划行落库
            planMapper.insert(plan);
        } catch (DuplicateKeyException e) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "执行计划唯一冲突（同项同时点重复生成，幂等拒绝）：orderNo=" + order.getOrderNo());
        }
    }

    /**
     * 医嘱头三态推进（W-33 契约）：临时单次 TRANSFERRED→COMPLETED；长期首个回签
     * TRANSFERRED→EXECUTING（随后穿透终态判定——单点频次当日全部回签即完成场景）；长期
     * 全部计划实例终态 EXECUTING→COMPLETED。终态/他态（STOPPED/CANCELLED/COMPLETED）无
     * 推进面——回签仅落计划面（executed 事件仍发布，M13 费用确认需要）。
     *
     * @param order    回签关联医嘱行（status 为回签前实态，迁移后内存同步），非空
     * @param operator 操作者员工 ID（状态机审计面），非空
     */
    private void advanceOrderHead(MedicalOrder order, long operator) {
        // 临时医嘱：单次回签即完成（多明细行后续回签头已 COMPLETED——仅落计划面）
        if (OrderClass.STAT.getCode().equals(order.getOrderClass())) {
            if (OrderStatus.TRANSFERRED.getCode().equals(order.getStatus())) {
                stateMachine.transition(order, OrderStatus.COMPLETED, REASON_STAT_CONFIRM, operator);
            } else {
                log.info("临时医嘱头已迁移（多明细后续回签仅落计划面）：orderNo={}，status={}", order.getOrderNo(), order.getStatus());
            }
            return;
        }
        // 长期医嘱：首个回签推进执行中（迁移后内存同步目标态，穿透终态判定）
        if (OrderStatus.TRANSFERRED.getCode().equals(order.getStatus())) {
            stateMachine.transition(order, OrderStatus.EXECUTING, REASON_FIRST_CONFIRM, operator);
        }
        if (OrderStatus.EXECUTING.getCode().equals(order.getStatus())) {
            // 数据库读操作：在途 PENDING 计划计数（0=全部计划实例终态——EXECUTED/CANCELLED 均终态）
            Long pending = planMapper.selectCount(Wrappers.<OrderExecutePlan>lambdaQuery()
                    .eq(OrderExecutePlan::getOrderId, order.getId())
                    .eq(OrderExecutePlan::getStatus, PlanStatus.PENDING.getCode()));
            if (pending != null && pending == 0) {
                stateMachine.transition(order, OrderStatus.COMPLETED, REASON_ALL_PLANS_TERMINAL, operator);
            }
            return;
        }
        // 长期医嘱终态/他态（STOPPED/CANCELLED/COMPLETED）：头无推进面，回签仅落计划面
        log.info("医嘱头状态无回签推进面（终态/他态仅落计划面）：orderNo={}，status={}", order.getOrderNo(), order.getStatus());
    }

    /**
     * 计划拆分事件发布（V800 id 43 载荷，按医嘱逐条）。
     *
     * @param order    目标医嘱行，非空
     * @param visit    关联就诊行，非空
     * @param planDate 计划日期，非空
     * @param created  本批新生成计划行（planNos/planTimes 下标对齐取数面），非空
     */
    private void publishGenerated(
            MedicalOrder order, InpatientVisit visit, LocalDate planDate, List<OrderExecutePlan> created) {
        events.publishEvent(new InpatientDomainEvent(
                InpatientMessagingConstants.EVENT_ORDER_PLAN_GENERATED,
                new OrderPlanGeneratedPayload(
                        order.getOrderNo(),
                        visit.getVisitId(),
                        order.getPatientId(),
                        planDate,
                        created.stream().map(OrderExecutePlan::getPlanNo).toList(),
                        created.stream()
                                .map(plan -> plan.getPlanTime().toLocalTime().format(TIME_POINT_FORMAT))
                                .toList())));
    }

    /** 医嘱集分批切片（每批 ≤500，断点续跑事务边界）。 */
    private static List<List<MedicalOrder>> partition(List<MedicalOrder> orders) {
        List<List<MedicalOrder>> chunks = new ArrayList<>();
        for (int i = 0; i < orders.size(); i += DECOMPOSE_BATCH_SIZE) {
            chunks.add(orders.subList(i, Math.min(i + DECOMPOSE_BATCH_SIZE, orders.size())));
        }
        return chunks;
    }

    /** 操作者上下文回退（补偿面——转抄链已校验非空，防御回退 system 与审计列默认同源）。 */
    private static String operatorOrSystem() {
        String operator = OperatorContextHolder.get();
        return operator == null || operator.isBlank() ? SYSTEM_OPERATOR : operator;
    }

    /** 按医嘱号定位行（未命中定性 IP-1009；逻辑删由 @TableLogic 自动过滤）。 */
    private MedicalOrder requireOrder(String orderNo) {
        MedicalOrder order =
                orderMapper.selectOne(Wrappers.<MedicalOrder>lambdaQuery().eq(MedicalOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(InpatientErrorCode.ORDER_NOT_FOUND, HttpStatus.NOT_FOUND, "医嘱不存在：" + orderNo);
        }
        return order;
    }

    /** 按医嘱主键定位行（回签链关联定位；未命中定性 IP-1009 数据不一致——fail-closed）。 */
    private MedicalOrder requireOrderById(Long orderId) {
        MedicalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(
                    InpatientErrorCode.ORDER_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "执行计划关联医嘱不存在（数据不一致）：orderId(pk)=" + orderId);
        }
        return order;
    }

    /** 按计划号定位行（未命中定性 IP-1014；逻辑删由 @TableLogic 自动过滤）。 */
    private OrderExecutePlan requirePlan(String planNo) {
        OrderExecutePlan plan =
                planMapper.selectOne(Wrappers.<OrderExecutePlan>lambdaQuery().eq(OrderExecutePlan::getPlanNo, planNo));
        if (plan == null) {
            throw new BizException(InpatientErrorCode.PLAN_NOT_FOUND, HttpStatus.NOT_FOUND, "执行计划不存在：" + planNo);
        }
        return plan;
    }

    /** 按就诊主键定位行（未命中定性 IP-1007 数据不一致；逻辑删由 @TableLogic 自动过滤）。 */
    private InpatientVisit requireVisitById(Long visitPk) {
        InpatientVisit visit = visitMapper.selectById(visitPk);
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "医嘱关联住院就诊不存在（数据不一致）：visitId(pk)=" + visitPk);
        }
        return visit;
    }

    /**
     * 操作者标识解析为员工 ID（REST 面操作者上下文；缺失/非数字显式 IP-1022 拒绝——
     * 与请求承载的 executorId 并行口径：executorId 落计划行/事件，操作者上下文落审计列与状态机）。
     *
     * @return 员工 ID（OperatorContextHolder 运行态 userId 数字形态）
     * @throws BizException IP-1022 操作者标识缺失或非数字时触发
     */
    private static long parseOperatorAsEmployeeId() {
        String operator = OperatorContextHolder.get();
        if (operator == null || !operator.matches("\\d+")) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "操作者标识缺失或非数字（无法定位回签操作主体）：" + maskOperator(operator));
        }
        return Long.parseLong(operator);
    }

    /** 工号脱敏（等保三级口径，禁明文工号出 ProblemDetail/日志）：首尾各留 1 位，中段 ***。 */
    private static String maskOperator(String operator) {
        if (operator == null || operator.length() <= 2) {
            return "***";
        }
        return operator.charAt(0) + "***" + operator.charAt(operator.length() - 1);
    }

    /** 空串判定（null 或全空白）。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
