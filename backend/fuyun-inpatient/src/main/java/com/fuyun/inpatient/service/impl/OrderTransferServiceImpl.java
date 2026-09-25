package com.fuyun.inpatient.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.inpatient.api.InpatientErrorCode;
import com.fuyun.inpatient.api.payload.OrderTransferredPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.TransferCheckRequest;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import com.fuyun.inpatient.entity.OrderExecutePlan;
import com.fuyun.inpatient.entity.OrderTransferLog;
import com.fuyun.inpatient.enums.CheckConclusion;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.OrderType;
import com.fuyun.inpatient.enums.PlanStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import com.fuyun.inpatient.mapper.OrderTransferLogMapper;
import com.fuyun.inpatient.service.OrderPlanService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.service.OrderTransferService;
import com.fuyun.inpatient.vo.OrderPlanVO;
import com.fuyun.inpatient.vo.TransferWorklistVO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 医嘱转抄与执行计划服务实现（FU-M04-06 上，V906 两表业务面）。转抄核对链（transferCheck，
 * 整批单事务）：逐条守卫（已 TRANSFERRED 幂等跳过/非 AUDITED 拒 IP-1010/输血类 BLOOD 缺
 * 第二核对人拒 IP-1016——item 级高危药标记 V904 无落列，药品高危分级字典对接 P3 注记/
 * 结论 REJECTED 拦截）→ 状态机唯一迁移（GC17，留痕随状态机自动落库）→ 转抄台账（V906
 * order_transfer_log 双人核对留痕）→ 事务内发布 inpatient.order.transferred（V800 id 42
 * 载荷，transferType=医嘱类型子键小写形态——transferred 登记名不带子键故类型入载荷）→
 * 临时医嘱同步按明细行生成单次执行计划（plan_time=转抄时点+默认准备窗口，多项明细
 * plan_no 各异）；长期医嘱即时补生成当日剩余时点计划（OrderPlanService.compensateToday
 * ——Task 8 衔接面，转抄事务内加入）。嘱托触发（standbyTrigger）：长期备用嘱按需生成当次计划实例，医嘱头
 * 不迁移（回签面推进归 Task 8 W-33）。计划查询（listPlans）：日期窗口+病区分页，关联号
 * 映射批量承载免行级 N+1。转科三分钩子（redirectPlansOnWardTransfer）：临时 PENDING 计划
 * 病区重定向、长期 PENDING 计划作废（TransferServiceImpl 阶段②回接面）。
 * 线程安全：无状态 singleton；写操作 @Transactional 收口（钩子面 REQUIRED 传播加入编排事务）。
 */
@Slf4j
public class OrderTransferServiceImpl implements OrderTransferService {

    /** 班次词表：白班（照 V801 病区班次定义 code，08:00–16:00） */
    private static final String SHIFT_DAY = "DAY";

    /** 班次词表：小夜班（16:00–24:00） */
    private static final String SHIFT_EVENING = "EVENING";

    /** 班次词表：大夜班（00:00–08:00） */
    private static final String SHIFT_NIGHT = "NIGHT";

    /** 班次词表全集（worklist 入参与计划落班的校验/取值面——V906 shift 列词表同源） */
    private static final Set<String> SHIFT_VOCABULARY = Set.of(SHIFT_DAY, SHIFT_EVENING, SHIFT_NIGHT);

    /** 白班窗口起点（08:00，V801 病区班次定义同源） */
    private static final LocalTime DAY_START = LocalTime.of(8, 0);

    /** 白班窗口终点=小夜班起点（16:00，左闭右开） */
    private static final LocalTime DAY_END = LocalTime.of(16, 0);

    /** 临时单次/嘱托触发计划的默认准备窗口：计划时点=生成时点+15 分钟（即刻执行的备药准备缓冲，常量承载——P3 可提配置面） */
    private static final Duration DEFAULT_PREPARE_WINDOW = Duration.ofMinutes(15);

    /** 转抄迁移留痕原因（状态机 reason 面） */
    private static final String REASON_TRANSFER_CHECK = "护士转抄核对";

    private final MedicalOrderMapper orderMapper;

    private final MedicalOrderItemMapper itemMapper;

    private final OrderTransferLogMapper transferLogMapper;

    private final OrderExecutePlanMapper planMapper;

    private final InpatientVisitMapper visitMapper;

    private final InpatientSeqGate seqGate;

    private final OrderStateMachineService stateMachine;

    private final OrderPlanService orderPlanService;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 InpatientWebConfig @Import）。
     *
     * @param orderMapper        医嘱主表 mapper，非空；工作台聚合与转抄守卫定位
     * @param itemMapper         医嘱明细 mapper，非空；单次计划按明细行生成取数
     * @param transferLogMapper  转抄记录 mapper，非空；双人核对台账落行
     * @param planMapper         执行计划 mapper，非空；计划开立/查询/作废/重定向
     * @param visitMapper        住院就诊 mapper，非空；病区聚合与号映射
     * @param seqGate            住院业务号发号器（PL 计划号），非空
     * @param stateMachine       医嘱状态机服务（状态迁移唯一执行面），非空
     * @param orderPlanService   医嘱执行计划服务（长期医嘱当日增量补偿——Task 8 衔接面），非空
     * @param events             进程内事件发布器（AFTER_COMMIT 出 MQ），非空
     */
    public OrderTransferServiceImpl(
            MedicalOrderMapper orderMapper,
            MedicalOrderItemMapper itemMapper,
            OrderTransferLogMapper transferLogMapper,
            OrderExecutePlanMapper planMapper,
            InpatientVisitMapper visitMapper,
            InpatientSeqGate seqGate,
            OrderStateMachineService stateMachine,
            OrderPlanService orderPlanService,
            ApplicationEventPublisher events) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.transferLogMapper = transferLogMapper;
        this.planMapper = planMapper;
        this.visitMapper = visitMapper;
        this.seqGate = seqGate;
        this.stateMachine = stateMachine;
        this.orderPlanService = orderPlanService;
        this.events = events;
    }

    /**
     * 转抄工作台待转抄列表：病区在院就诊（ADMITTED）→ AUDITED 医嘱分页聚合（开立时间倒序）；
     * 班次过滤按开立时点落班窗口（V801 定义，左闭右开）。出院申请中患者的在途医嘱由出院
     * 清理面收口（FU-M04-07），本面仅聚合 ADMITTED。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<TransferWorklistVO> worklist(String wardId, String shift, int page, int size) {
        // 过滤键守卫（Web 层 @NotBlank 兜底，服务面覆盖模块内直调场景）
        if (isBlank(wardId)) {
            throw paramInvalid("wardId", wardId);
        }
        if (shift != null && !SHIFT_VOCABULARY.contains(shift)) {
            throw paramInvalid("shift", shift);
        }
        // 数据库读操作：病区在院就诊全集（current_ward_id 定位；就诊号映射同源取数免二次查询）
        List<InpatientVisit> visits = visitMapper.selectList(Wrappers.<InpatientVisit>lambdaQuery()
                .eq(InpatientVisit::getCurrentWardId, wardId)
                .eq(InpatientVisit::getStatus, VisitStatus.ADMITTED.getCode()));
        if (visits.isEmpty()) {
            // 空集直过（病区无在院患者——无待转抄面）
            return PageResult.of(List.of(), page, size, 0L);
        }
        Map<Long, InpatientVisit> visitIndex =
                visits.stream().collect(Collectors.toMap(InpatientVisit::getId, Function.identity()));
        // 班次开立窗口（可空=全班次；窗口左闭右开，EVENING 跨零点收口于次日 00:00）
        OffsetDateTime[] window = shift == null ? null : shiftWindowOf(LocalDate.now(), shift);
        // 数据库读操作：待转抄医嘱分页（visit_id IN 就诊集 + AUDITED + 开立时点班次窗口；
        // 班次条件缺席时不挂 ge/lt 段——避免条件布尔重载的实参预取空数组下标）
        LambdaQueryWrapper<MedicalOrder> query = Wrappers.<MedicalOrder>lambdaQuery()
                .in(MedicalOrder::getVisitId, visitIndex.keySet())
                .eq(MedicalOrder::getStatus, OrderStatus.AUDITED.getCode())
                .orderByDesc(MedicalOrder::getOrderedAt);
        if (window != null) {
            query.ge(MedicalOrder::getOrderedAt, window[0]).lt(MedicalOrder::getOrderedAt, window[1]);
        }
        Page<MedicalOrder> result = orderMapper.selectPage(new Page<>(page + 1, size), query);
        return PageResult.of(
                result.getRecords().stream()
                        .map(order -> TransferWorklistVO.from(
                                order, visitIndex.get(order.getVisitId()).getVisitId()))
                        .toList(),
                page,
                size,
                result.getTotal());
    }

    /**
     * 批量转抄核对（整批单事务）：守卫前置（结论 REJECTED 拦截/转抄护士缺失）→ 逐条
     * 幂等跳过或完整转抄链（迁移+台账+事件+临时单次计划）→ 汇总留痕。
     */
    @Override
    @Transactional
    public void transferCheck(TransferCheckRequest req) {
        // 核对结论限定：REJECTED=核对不符未转抄（临床流程退回医生站），禁入转抄面（台账只承载
        // 实际发生的转抄留痕——REJECTED 词表保完整供后续「核对不符留痕」扩展面）
        if (req.conclusion() != CheckConclusion.PASSED) {
            throw new BizException(
                    InpatientErrorCode.TRANSFER_CHECK_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "转抄核对结论不符（REJECTED）禁止转抄：核对不符临床流程退回医生站处理");
        }
        // 转抄护士守卫（Web 层 @NotBlank 兜底，服务面覆盖模块内直调场景）
        if (isBlank(req.transferNurseId())) {
            throw paramInvalid("transferNurseId", req.transferNurseId());
        }
        long operator = parseOperatorAsEmployeeId();
        int transferredCount = 0;
        int skippedCount = 0;
        for (String orderNo : req.orderNos()) {
            MedicalOrder order = requireOrder(orderNo);
            // 转抄锁定幂等：已 TRANSFERRED 直接过（重复提交/并发窗口零副作用——整批语义容错）
            if (OrderStatus.TRANSFERRED.getCode().equals(order.getStatus())) {
                skippedCount++;
                log.info("转抄幂等跳过（已 TRANSFERRED）：orderNo={}，operator={}", orderNo, operator);
                continue;
            }
            // 状态守卫：转抄仅限 AUDITED（待转抄态）；他态拒 IP-1010 传播整批回滚
            if (!OrderStatus.AUDITED.getCode().equals(order.getStatus())) {
                throw new BizException(
                        InpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                        HttpStatus.CONFLICT,
                        "医嘱状态不允许转抄（仅 AUDITED 待转抄态）：orderNo=" + orderNo + "，当前状态=" + order.getStatus());
            }
            OrderType orderType = requireOrderType(order);
            // 双人核对强制：输血类 BLOOD 缺第二核对人拒 IP-1016（高危强制面——调研依据 5；
            // item 级高危药标记 V904 无落列，药品高危分级字典对接 P3 注记）
            if (orderType == OrderType.BLOOD && isBlank(req.secondCheckerId())) {
                log.warn("转抄核对被拒（输血类缺第二核对人）：orderNo={}，operator={}", orderNo, operator);
                throw new BizException(
                        InpatientErrorCode.TRANSFER_CHECK_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "输血类医嘱转抄须双人核对（第二核对人必填）：" + orderNo);
            }
            InpatientVisit visit = requireVisitByPk(order.getVisitId());
            OffsetDateTime transferredAt = OffsetDateTime.now();
            // 状态机唯一迁移面（AUDITED→TRANSFERRED，留痕随状态机自动落 order_status_log）
            stateMachine.transition(order, OrderStatus.TRANSFERRED, REASON_TRANSFER_CHECK, operator);
            // 转抄台账落行（双人核对留痕：转抄护士/时点/结论/第二核对人）
            insertTransferLog(order, req, transferredAt, operator);
            // 事务内发布转抄事件（AFTER_COMMIT 出 fy.topic；M05 据此生成临时医嘱单次执行单）；
            // transferType=医嘱类型子键小写形态（登记名不带子键，类型入载荷）；禁患者姓名/诊断文本
            events.publishEvent(new InpatientDomainEvent(
                    InpatientMessagingConstants.EVENT_ORDER_TRANSFERRED,
                    new OrderTransferredPayload(
                            order.getOrderNo(),
                            visit.getVisitId(),
                            order.getPatientId(),
                            orderType.subKey(),
                            transferredAt.toInstant())));
            // 临时医嘱同步生成单次执行计划；长期医嘱即时补生成当日剩余时点计划（Task 8 补偿
            // 面衔接——转抄事务内加入，频次字典缺失等异常仅 warn 不阻断转抄主链，勿双头生成）
            if (OrderClass.STAT.getCode().equals(order.getOrderClass())) {
                createSinglePlans(order, visit, transferredAt, operator);
            } else {
                orderPlanService.compensateToday(orderNo);
            }
            transferredCount++;
        }
        log.info(
                "批量转抄核对完成：提交 {} 条，转抄 {} 条，幂等跳过 {} 条，transferNurse={}，operator={}",
                req.orderNos().size(),
                transferredCount,
                skippedCount,
                req.transferNurseId(),
                operator);
    }

    /** 执行计划分页查询：日期窗口+病区分页（计划时点升序），关联号映射批量承载免行级 N+1。 */
    @Override
    @Transactional(readOnly = true)
    public PageResult<OrderPlanVO> listPlans(LocalDate date, String wardId, int page, int size) {
        if (date == null) {
            // 日期必填（计划视图以日为轴；Web 层 required=true 兜底，服务面覆盖模块内直调场景）
            throw new BizException(InpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "执行计划查询日期必填（date）");
        }
        // 当日窗口 [00:00, 次日 00:00)（服务器时区口径，与计划落库 OffsetDateTime.now() 同源）
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        OffsetDateTime start = date.atStartOfDay().atOffset(offset);
        OffsetDateTime end = date.plusDays(1).atStartOfDay().atOffset(offset);
        // 数据库读操作：计划分页（0 基请求转 MP 1 基 current）
        Page<OrderExecutePlan> result = planMapper.selectPage(
                new Page<>(page + 1, size),
                Wrappers.<OrderExecutePlan>lambdaQuery()
                        .ge(OrderExecutePlan::getPlanTime, start)
                        .lt(OrderExecutePlan::getPlanTime, end)
                        .eq(!isBlank(wardId), OrderExecutePlan::getWardId, wardId)
                        .orderByAsc(OrderExecutePlan::getPlanTime));
        if (result.getRecords().isEmpty()) {
            return PageResult.of(List.of(), page, size, result.getTotal());
        }
        // 批量号映射：计划行→医嘱行（order_no）→就诊行（I 型 visit_id）两跳各一次批量查询
        Map<Long, MedicalOrder> orderIndex = orderMapper
                .selectBatchIds(result.getRecords().stream()
                        .map(OrderExecutePlan::getOrderId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(MedicalOrder::getId, Function.identity()));
        Map<Long, InpatientVisit> visitIndex =
                visitMapper
                        .selectBatchIds(orderIndex.values().stream()
                                .map(MedicalOrder::getVisitId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(InpatientVisit::getId, Function.identity()));
        List<OrderPlanVO> rows = new ArrayList<>(result.getRecords().size());
        for (OrderExecutePlan plan : result.getRecords()) {
            MedicalOrder order = requireMappedOrder(orderIndex, plan);
            InpatientVisit visit = requireMappedVisit(visitIndex, order.getVisitId());
            rows.add(OrderPlanVO.from(plan, order.getOrderNo(), visit.getVisitId()));
        }
        return PageResult.of(rows, page, size, result.getTotal());
    }

    /**
     * 嘱托按需触发单次计划：长期备用嘱按明细行生成当次计划实例（多次触发多次台账，plan_no
     * 各异——不重复计价由 M13 唯一键兜底）；医嘱头状态不迁移（回签面推进归 W-33 契约）。
     */
    @Override
    @Transactional
    public List<OrderPlanVO> standbyTrigger(String orderNo) {
        MedicalOrder order = requireOrder(orderNo);
        // 嘱托限定：仅 standby_flag=true 的长期医嘱可用触发面（开立 L4 守卫的对偶消费面）
        if (!Boolean.TRUE.equals(order.getStandbyFlag())) {
            throw new BizException(
                    InpatientErrorCode.PARAM_FORMAT_INVALID,
                    HttpStatus.BAD_REQUEST,
                    "非嘱托医嘱禁用嘱托触发面（standby_flag=false）：" + orderNo);
        }
        // 状态限定：须经转抄（TRANSFERRED/EXECUTING——AUDITED 前置未完成禁触发计划）
        if (!OrderStatus.TRANSFERRED.getCode().equals(order.getStatus())
                && !OrderStatus.EXECUTING.getCode().equals(order.getStatus())) {
            throw new BizException(
                    InpatientErrorCode.ORDER_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "嘱托触发要求医嘱已经转抄（TRANSFERRED/EXECUTING）：orderNo=" + orderNo + "，当前状态=" + order.getStatus());
        }
        InpatientVisit visit = requireVisitByPk(order.getVisitId());
        long operator = parseOperatorAsEmployeeId();
        OffsetDateTime triggerAt = OffsetDateTime.now();
        // 单次计划生成与临时医嘱转抄同步生成共用实现（plan_time=触发时点+默认准备窗口）
        List<OrderExecutePlan> created = createSinglePlans(order, visit, triggerAt, operator);
        log.info(
                "嘱托触发完成：orderNo={}，visitNo={}，生成计划 {} 条，operator={}",
                orderNo,
                visit.getVisitId(),
                created.size(),
                operator);
        return created.stream()
                .map(plan -> OrderPlanVO.from(plan, order.getOrderNo(), visit.getVisitId()))
                .toList();
    }

    /**
     * 转科编排计划三分钩子（编排事务内）：临时 PENDING 计划重定向目标病区 + 长期 PENDING
     * 计划批量作废（Task 4 javadoc 冻结口径——医嘱停嘱由阶段①承载，费用不改写归 M13 日切）。
     */
    @Override
    @Transactional
    public void redirectPlansOnWardTransfer(Long visitId, String toWardId, String operator) {
        // 长期医嘱 PENDING 计划作废（停嘱联动 cancelFuturePlans 仅覆盖未来时点，本面补齐长期
        // 在途全量 PENDING——转科瞬间未执行临时计划随患者转移而长期计划作废，04 Spec 边界验收）
        List<Long> longOrderIds = orderIdsOfVisit(visitId, OrderClass.LONG);
        int cancelled = longOrderIds.isEmpty() ? 0 : planMapper.cancelPendingByOrderIds(longOrderIds, operator);
        // 临时医嘱 PENDING 计划保留随患者（ward_id 批量重定向目标病区；已执行计划归历史不动）
        List<Long> statOrderIds = orderIdsOfVisit(visitId, OrderClass.STAT);
        int redirected =
                statOrderIds.isEmpty() ? 0 : planMapper.redirectWardByOrderIds(statOrderIds, toWardId, operator);
        log.info(
                "转科计划三分完成：visitId(pk)={}，长期计划作废 {} 条，临时计划重定向 {} 条 → wardId={}，operator={}",
                visitId,
                cancelled,
                redirected,
                toWardId,
                operator);
    }

    /**
     * 单次执行计划生成（临时医嘱转抄同步生成/嘱托按需触发共用）：按明细行逐行开立计划实例
     * （计划粒度=明细行——执行回签与计价按项对齐）；plan_time=生成基准时点+默认准备窗口、
     * shift=基准时点落班、plan_no=PL 流水逐行签发。
     *
     * @param order    所属医嘱行，非空
     * @param visit    关联就诊行（病区/就诊主键取数面），非空
     * @param baseTime 生成基准时点（转抄时点/触发时点），非空
     * @param operator 操作者（审计留痕），非空
     * @return 已落库计划行全集，非空（明细行空集时为空清单——开立不变量保证非空）
     */
    private List<OrderExecutePlan> createSinglePlans(
            MedicalOrder order, InpatientVisit visit, OffsetDateTime baseTime, long operator) {
        // 数据库读操作：医嘱明细行全集（计划按明细行粒度开立）
        List<MedicalOrderItem> items = itemMapper.selectList(
                Wrappers.<MedicalOrderItem>lambdaQuery().eq(MedicalOrderItem::getOrderId, order.getId()));
        OffsetDateTime planTime = baseTime.plus(DEFAULT_PREPARE_WINDOW);
        String shift = currentShift(baseTime);
        String operatorText = String.valueOf(operator);
        List<OrderExecutePlan> created = new ArrayList<>(items.size());
        for (MedicalOrderItem item : items) {
            OrderExecutePlan plan = new OrderExecutePlan();
            plan.setPlanNo(seqGate.nextNo("PL"));
            plan.setOrderId(order.getId());
            plan.setOrderItemId(item.getId());
            plan.setVisitId(visit.getId());
            plan.setWardId(visit.getCurrentWardId());
            plan.setPlanTime(planTime);
            plan.setShift(shift);
            plan.setStatus(PlanStatus.PENDING.getCode());
            plan.setCreatedBy(operatorText);
            plan.setUpdatedBy(operatorText);
            try {
                // 数据库写操作：计划行落库（uk_order_execute_plan_no/uk_plan_order_item_time 兜底幂等）
                planMapper.insert(plan);
            } catch (DuplicateKeyException e) {
                // 并发窗口同项同时点重复生成（微秒级同瞬触发）——幂等拒绝定性冲突
                throw new BizException(
                        InpatientErrorCode.CONFLICT,
                        HttpStatus.CONFLICT,
                        "执行计划唯一冲突（同项同时点重复生成，幂等拒绝）：orderNo=" + order.getOrderNo());
            }
            created.add(plan);
        }
        return created;
    }

    /**
     * 转抄台账落行（V906 order_transfer_log 只增）：双人核对留痕四要素（转抄护士/时点/结论/
     * 第二核对人——高危/输血类强制非空已由前置守卫保证）。
     *
     * @param order         转抄医嘱行（状态已 TRANSFERRED），非空
     * @param req           批量转抄入参（护士/结论/第二核对人取数面），非空
     * @param transferredAt 转抄时点（服务器时间），非空
     * @param operator      操作者员工 ID（审计列），非空
     */
    private void insertTransferLog(
            MedicalOrder order, TransferCheckRequest req, OffsetDateTime transferredAt, long operator) {
        String operatorText = String.valueOf(operator);
        OrderTransferLog row = new OrderTransferLog();
        row.setOrderId(order.getId());
        row.setTransferNurse(req.transferNurseId());
        row.setTransferredAt(transferredAt);
        row.setConclusion(req.conclusion().getCode());
        row.setSecondCheckerId(req.secondCheckerId());
        row.setCreatedBy(operatorText);
        row.setUpdatedBy(operatorText);
        // 数据库写操作：转抄台账只增落库（V906 order_transfer_log）
        transferLogMapper.insert(row);
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

    /** 按就诊主键定位行（未命中定性 IP-1007 数据不一致；逻辑删由 @TableLogic 自动过滤）。 */
    private InpatientVisit requireVisitByPk(Long visitPk) {
        InpatientVisit visit = visitMapper.selectById(visitPk);
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "医嘱关联住院就诊不存在（数据不一致）：visitId(pk)=" + visitPk);
        }
        return visit;
    }

    /** 医嘱类型词表裁决（词表外=主数据脏数据，fail-closed 拒 IP-1023——OrderAuditServiceImpl 同款）。 */
    private OrderType requireOrderType(MedicalOrder order) {
        OrderType orderType = OrderType.fromCode(order.getOrderType());
        if (orderType == null) {
            throw new BizException(
                    InpatientErrorCode.CONFLICT,
                    HttpStatus.CONFLICT,
                    "医嘱类型词表外脏数据（转抄链无法裁决）：orderNo=" + order.getOrderNo() + "，orderType=" + order.getOrderType());
        }
        return orderType;
    }

    /** 批量面医嘱行定位（缺失定性 IP-1009 数据不一致——fail-closed）。 */
    private MedicalOrder requireMappedOrder(Map<Long, MedicalOrder> orderIndex, OrderExecutePlan plan) {
        MedicalOrder order = orderIndex.get(plan.getOrderId());
        if (order == null) {
            throw new BizException(
                    InpatientErrorCode.ORDER_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "执行计划关联医嘱不存在（数据不一致）：planNo=" + plan.getPlanNo());
        }
        return order;
    }

    /** 批量面就诊行定位（缺失定性 IP-1007 数据不一致——fail-closed）。 */
    private InpatientVisit requireMappedVisit(Map<Long, InpatientVisit> visitIndex, Long visitPk) {
        InpatientVisit visit = visitIndex.get(visitPk);
        if (visit == null) {
            throw new BizException(
                    InpatientErrorCode.VISIT_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "执行计划关联就诊不存在（数据不一致）：visitId(pk)=" + visitPk);
        }
        return visit;
    }

    /** 就诊下指定分类医嘱主键集（转科三分钩子的分野查询面；含全状态——计划归属以计划行状态裁决）。 */
    private List<Long> orderIdsOfVisit(Long visitId, OrderClass orderClass) {
        return orderMapper
                .selectList(Wrappers.<MedicalOrder>lambdaQuery()
                        .eq(MedicalOrder::getVisitId, visitId)
                        .eq(MedicalOrder::getOrderClass, orderClass.getCode()))
                .stream()
                .map(MedicalOrder::getId)
                .toList();
    }

    /**
     * 班次窗口求取（V801 病区班次定义同源，窗口左闭右开）：DAY 当日 08:00–16:00 /
     * EVENING 当日 16:00–次日 00:00 / NIGHT 当日 00:00–08:00。
     *
     * @param day   窗口基准日，非空
     * @param shift 班次 code（词表内已裁决），非空
     * @return [窗口起点, 窗口终点) 二元素数组，非空
     */
    private static OffsetDateTime[] shiftWindowOf(LocalDate day, String shift) {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        if (SHIFT_EVENING.equals(shift)) {
            // 小夜班跨零点：终点收口于次日 00:00（24:00 不存在时刻形态）
            return new OffsetDateTime[] {
                day.atTime(DAY_END).atOffset(offset),
                day.plusDays(1).atStartOfDay().atOffset(offset)
            };
        }
        // 大夜班起于当日 00:00；白班 08:00–16:00
        LocalTime start = SHIFT_NIGHT.equals(shift) ? LocalTime.MIDNIGHT : DAY_START;
        LocalTime end = SHIFT_NIGHT.equals(shift) ? DAY_START : DAY_END;
        return new OffsetDateTime[] {
            day.atTime(start).atOffset(offset), day.atTime(end).atOffset(offset)
        };
    }

    /**
     * 时点落班裁决（计划 shift 落值与班次词表共用口径；包级可见供单测直测三窗口边界）。
     *
     * @param time 待裁决时点，非空
     * @return 班次 code（DAY/EVENING/NIGHT 三值词表），非空
     */
    static String currentShift(OffsetDateTime time) {
        LocalTime local = time.toLocalTime();
        // 窗口左闭右开：08:00 整点起白班、16:00 整点起小夜班、00:00（含）–08:00 大夜班
        if (!local.isBefore(DAY_START) && local.isBefore(DAY_END)) {
            return SHIFT_DAY;
        }
        if (!local.isBefore(DAY_END)) {
            return SHIFT_EVENING;
        }
        return SHIFT_NIGHT;
    }

    /**
     * 操作者标识解析为员工 ID（REST 面操作者上下文；缺失/非数字显式 IP-1022 拒绝——
     * W-22⑦ 禁裸 parse 同款守卫）。
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
                    "操作者标识缺失或非数字（无法定位转抄操作主体）：" + maskOperator(operator));
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

    /** 入参格式非法（IP-1022）统一构造：词表外/结构校验不过。 */
    private static BizException paramInvalid(String field, String value) {
        return new BizException(
                InpatientErrorCode.PARAM_FORMAT_INVALID, HttpStatus.BAD_REQUEST, "入参格式非法——" + field + " 词表外：" + value);
    }

    /** 空串判定（null 或全空白）。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
