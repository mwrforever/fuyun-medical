package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.fuyun.inpatient.enums.CheckConclusion;
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
import com.fuyun.inpatient.service.OrderAuditService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.ExecuteConfirmVO;
import com.fuyun.inpatient.vo.OrderTraceVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.system.api.PracticeCheckPort;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 医嘱执行计划服务单测（Task 8 冻结九用例 + 覆盖率补面）：①日切分解（bid 长期医嘱→次日
 * 2 行、时点 08:00/16:00、generated 事件）②重复日切幂等（查前置零新行）③当日补偿（16:30
 * 转抄 bid→当日 0 行次日 2 行）④长期首个回签（TRANSFERRED→EXECUTING+executed 事件）
 * ⑤临时单次回签（TRANSFERRED→COMPLETED）⑥全部终态推进（EXECUTING→COMPLETED，end_at
 * 到期面）⑦重复回签幂等（返回当前状态零事件）⑧停嘱作废未来计划（复用 cancelFuturePlans
 * 已落库面）⑨trace 五环节聚合；补面：分批事务边界计数、字典缺失/prn/无时点/明细缺失/就诊
 * 缺失跳过面、批次失败续跑、补偿守卫、回签守卫（IP-1014/1015/1009/1007/1022）、首个回签
 * 穿透终态判定、GC26 注解 SQL 锚。审查修复环 R1 增面：跨日窗口守卫（qd 无 end_at 保持
 * EXECUTING 且次日仍入候选并排除 end_at 已过医嘱 / qd end_at=当日全终态→COMPLETED）、
 * 补偿冲突仅 warn 不阻断转抄主链。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工
 * 注册表信息；状态机 mock 以 doAnswer 同步内存态（生产语义：迁移后内存行置目标态）。
 */
@ExtendWith(MockitoExtension.class)
class OrderPlanServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行主键（order_execute_plan.visit_id 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 操作者员工 ID（回签操作与审计口径——与请求承载 executorId 并行） */
    private static final long OPERATOR = 2001L;

    /** 请求承载执行护士（W-33 契约：executorId 经请求落计划行与事件） */
    private static final long EXECUTOR_NURSE = 3001L;

    /** 在院病区（计划落值） */
    private static final String WARD = "W01";

    /** 目标长期医嘱号 */
    private static final String ORDER_NO = "MO2026092500001";

    /** 目标长期医嘱主键 */
    private static final long ORDER_PK = 9001L;

    /** 计划号（日切分解断言载体） */
    private static final String PLAN_NO = "PL2026092500001";

    @Mock
    private MedicalOrderMapper orderMapper;

    @Mock
    private MedicalOrderItemMapper itemMapper;

    @Mock
    private OrderExecutePlanMapper planMapper;

    @Mock
    private OrderFrequencyMapper frequencyMapper;

    @Mock
    private OrderAuditMapper auditMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private OrderTransferLogMapper transferLogMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private InpatientSeqGate seqGate;

    @Mock
    private OrderStateMachineService stateMachine;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private OrderAuditService orderAuditService;

    @Mock
    private PracticeCheckPort practiceCheckPort;

    @Mock
    private AllergyChecker allergyChecker;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<OrderExecutePlan> planCaptor;

    /** 分批事务计数锚（AbstractPlatformTransactionManager 最小实现——doBegin 计数） */
    private final AtomicInteger begunTransactions = new AtomicInteger();

    private TransactionTemplate transactionTemplate;

    private OrderPlanServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（八实体查询面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrderItem.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecutePlan.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderFrequency.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderAudit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderStatusLog.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderTransferLog.class);
    }

    @BeforeEach
    void setUp() {
        // 无资源事务管理器（iot 段先例形态）：仅承载分批事务边界计数（每 500 医嘱一事务断言锚）
        transactionTemplate = new TransactionTemplate(new AbstractPlatformTransactionManager() {

            @Override
            protected Object doGetTransaction() {
                // 无资源事务令牌：同步激活由父类统一完成，令牌本身无消费方
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                // 分批事务边界计数：每批一事务（brief 冻结断言锚）
                begunTransactions.incrementAndGet();
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // 无资源 commit：提交点即 afterCommit 同步回调触发点
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // 无资源 rollback：批次失败续跑路径由调用方 catch 承载
            }
        });
        service = new OrderPlanServiceImpl(
                orderMapper,
                itemMapper,
                planMapper,
                frequencyMapper,
                auditMapper,
                statusLogMapper,
                transferLogMapper,
                visitMapper,
                seqGate,
                stateMachine,
                events,
                transactionTemplate);
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("用例①日切分解：bid 长期医嘱→次日 2 行计划（时点 08:00/16:00）+generated 事件（id 43 载荷逐字）")
    void decomposeNextDayGeneratesPlansPerFrequencyTimePoints() {
        LocalDate planDate = LocalDate.of(2026, 9, 26);
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any())).thenReturn(List.of(orderRow(OrderStatus.TRANSFERRED, "LONG", true, "bid")));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bid", "08:00,16:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("PL")).thenReturn("PL2026092600001", "PL2026092600002");

        int created = service.decomposeNextDay(planDate);

        // 单明细行 × 两时点 = 2 行（频次数量一致——brief 冻结断言）
        assertThat(created).isEqualTo(2);
        verify(planMapper, times(2)).insert(planCaptor.capture());
        assertThat(planCaptor.getAllValues())
                .extracting(plan -> plan.getPlanTime().toLocalTime().toString())
                .containsExactly("08:00", "16:00");
        assertThat(planCaptor.getAllValues()).allSatisfy(plan -> {
            assertThat(plan.getOrderId()).isEqualTo(ORDER_PK);
            assertThat(plan.getOrderItemId()).isEqualTo(101L);
            assertThat(plan.getVisitId()).isEqualTo(VISIT_PK);
            assertThat(plan.getWardId()).isEqualTo(WARD);
            assertThat(plan.getStatus()).isEqualTo(PlanStatus.PENDING.getCode());
            // 计划时点落班（V801 窗口共用口径——08:00 白班/16:00 小夜班）
            assertThat(plan.getShift()).isIn("DAY", "EVENING", "NIGHT");
            assertThat(plan.getPlanTime().toLocalDate()).isEqualTo(planDate);
            // 日切任务无操作者上下文——审计列回退 system
            assertThat(plan.getCreatedBy()).isEqualTo("system");
        });
        // generated 事件（V800 id 43 载荷逐字：m04OrderNo/visitId/patientId/planDate/planNos[]/planTimes[]；
        // 按医嘱逐条发布——planNos 与 planTimes 下标对齐）
        verify(events, times(1)).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_ORDER_PLAN_GENERATED);
        OrderPlanGeneratedPayload payload = (OrderPlanGeneratedPayload) event.payload();
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.planDate()).isEqualTo(planDate);
        assertThat(payload.planNos()).containsExactly("PL2026092600001", "PL2026092600002");
        assertThat(payload.planTimes()).containsExactly("08:00", "16:00");
    }

    @Test
    @DisplayName("用例②重复日切幂等：目标日已有行（查前置+唯一约束双保险）零新行零事件")
    void decomposeNextDayIsIdempotentWhenPlansAlreadyExist() {
        LocalDate planDate = LocalDate.of(2026, 9, 26);
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any())).thenReturn(List.of(orderRow(OrderStatus.EXECUTING, "LONG", false, "bid")));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bid", "08:00,16:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        // 查前置：目标日已有行（08:00 与 16:00 各一——重复日切场景）
        when(planMapper.selectList(any()))
                .thenReturn(List.of(
                        existingPlan(101L, planDate.atTime(8, 0).atOffset(offset)),
                        existingPlan(101L, planDate.atTime(16, 0).atOffset(offset))));

        int created = service.decomposeNextDay(planDate);

        assertThat(created).isZero();
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));
        verifyNoInteractions(events, seqGate);
    }

    @Test
    @DisplayName("用例③当日补偿：16:30 转抄 bid 医嘱→当日 0 行（16:00 已过）次日 2 行（日切全量）")
    void compensateTodaySkipsPastPointsAndNextDayDecomposeGeneratesAll() {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        OffsetDateTime at1630 = LocalDate.now().atTime(16, 30).atOffset(offset);
        MedicalOrder order = orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bid", "08:00,16:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());

        // 16:30 补偿：当日两时点（08:00/16:00）均已过——零生成零事件
        assertThat(service.compensateToday(ORDER_NO, at1630)).isZero();
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));
        verifyNoInteractions(events, seqGate);

        // 次日日切：全量时点 2 行（08:00/16:00）
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any())).thenReturn(List.of(order));
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("PL")).thenReturn("PL1", "PL2");
        assertThat(service.decomposeNextDay(tomorrow)).isEqualTo(2);
        verify(planMapper, times(2)).insert(planCaptor.capture());
        assertThat(planCaptor.getAllValues())
                .extracting(plan -> plan.getPlanTime().toLocalTime().toString())
                .containsExactly("08:00", "16:00");
        assertThat(planCaptor.getAllValues())
                .allSatisfy(plan -> assertThat(plan.getPlanTime().toLocalDate()).isEqualTo(tomorrow));
    }

    @Test
    @DisplayName("用例④长期首个回签：计划 CAS+医嘱头 TRANSFERRED→EXECUTING+executed 事件（id 47 载荷逐字）")
    void executeConfirmAdvancesLongOrderFromTransferredToExecuting() {
        OffsetDateTime before = OffsetDateTime.now();
        MedicalOrder order = orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid");
        // 状态机 mock 同步内存态（生产语义：迁移后内存行置目标态——出参 orderStatus 取数面）
        doAnswer(invocation -> {
                    invocation
                            .getArgument(0, MedicalOrder.class)
                            .setStatus(
                                    invocation.getArgument(1, OrderStatus.class).getCode());
                    return null;
                })
                .when(stateMachine)
                .transition(any(), any(), any(), anyLong());
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        // 首个回签后在途 PENDING 计划仍余（今日剩余时点）——头停留 EXECUTING
        when(planMapper.selectCount(any())).thenReturn(1L);

        ExecuteConfirmVO vo = service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));

        verify(stateMachine).transition(order, OrderStatus.EXECUTING, "长期医嘱首个执行回签", OPERATOR);
        verify(stateMachine, never()).transition(any(), eq(OrderStatus.COMPLETED), any(), anyLong());
        assertThat(vo).isEqualTo(new ExecuteConfirmVO(PLAN_NO, ORDER_NO, "EXECUTING", "EXECUTED"));
        // executed 事件（V800 id 47 载荷逐字：m04OrderNo/visitId/patientId/planNo/executedAt）
        verify(events, times(1)).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(InpatientMessagingConstants.EVENT_ORDER_EXECUTED);
        OrderExecutedPayload payload = (OrderExecutedPayload) event.payload();
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.planNo()).isEqualTo(PLAN_NO);
        // executedAt 缺省服务器时间（W-33 契约）
        assertThat(payload.executedAt()).isAfter(before.toInstant());
    }

    @Test
    @DisplayName("用例⑤临时单次回签：医嘱头 TRANSFERRED→COMPLETED（无终态判定穿透）")
    void executeConfirmCompletesStatOrderOnSinglePlanConfirm() {
        MedicalOrder order = orderRow(OrderStatus.TRANSFERRED, "STAT", false, null);
        doAnswer(invocation -> {
                    invocation
                            .getArgument(0, MedicalOrder.class)
                            .setStatus(
                                    invocation.getArgument(1, OrderStatus.class).getCode());
                    return null;
                })
                .when(stateMachine)
                .transition(any(), any(), any(), anyLong());
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        // 显式 executedAt（追溯补签场景）与途径核对结论携带
        OffsetDateTime explicitAt = OffsetDateTime.now().plusMinutes(5);

        ExecuteConfirmVO vo =
                service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, explicitAt, "双人核对通过"));

        verify(stateMachine).transition(order, OrderStatus.COMPLETED, "临时医嘱单次执行回签", OPERATOR);
        assertThat(vo).isEqualTo(new ExecuteConfirmVO(PLAN_NO, ORDER_NO, "COMPLETED", "EXECUTED"));
        // 请求承载 executorId/executedAt/routeCheckResult 逐参透传计划行 CAS（W-33 契约）
        ArgumentCaptor<OffsetDateTime> executedAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(planMapper)
                .casExecuteConfirm(
                        eq(PLAN_NO),
                        eq(String.valueOf(EXECUTOR_NURSE)),
                        executedAtCaptor.capture(),
                        eq("双人核对通过"),
                        eq(String.valueOf(OPERATOR)));
        assertThat(executedAtCaptor.getValue()).isEqualTo(explicitAt);
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("用例⑥全部终态推进：EXECUTING 医嘱末个计划回签（PENDING 计数 0 且 end_at 到期）→COMPLETED")
    void executeConfirmCompletesExecutingOrderWhenAllPlansTerminal() {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        MedicalOrder order = orderRow(OrderStatus.EXECUTING, "LONG", false, "bid");
        // 医嘱明示结束日=当日（审查修复环 R1 守卫生效面）：生命周期内计划确已穷尽方判 COMPLETED
        order.setEndAt(LocalDate.now().atTime(23, 59).atOffset(offset));
        doAnswer(invocation -> {
                    invocation
                            .getArgument(0, MedicalOrder.class)
                            .setStatus(
                                    invocation.getArgument(1, OrderStatus.class).getCode());
                    return null;
                })
                .when(stateMachine)
                .transition(any(), any(), any(), anyLong());
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        // 末个计划回签：在途 PENDING 计划清零（EXECUTED/CANCELLED 均终态）
        when(planMapper.selectCount(any())).thenReturn(0L);

        ExecuteConfirmVO vo = service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));

        verify(stateMachine).transition(order, OrderStatus.COMPLETED, "全部执行计划实例终态", OPERATOR);
        assertThat(vo).isEqualTo(new ExecuteConfirmVO(PLAN_NO, ORDER_NO, "COMPLETED", "EXECUTED"));
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("跨日窗口守卫：qd 无 end_at 当日末点回签后保持 EXECUTING（无 COMPLETED 推进）且次日仍入日切候选")
    void executeConfirmKeepsExecutingAcrossDayWindowWhenEndAtAbsent() {
        // 场景（审查修复环 R1 Critical 面）：qd 长期医嘱当日唯一时点回签后至次日 02:00 日切前
        // 「已有计划全终态」成立，但 end_at 为空——生命周期未穷尽，不得判 COMPLETED 静默中断
        MedicalOrder order = orderRow(OrderStatus.EXECUTING, "LONG", false, "qd");
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        // 当日唯一时点（qd 08:00）回签：在途 PENDING 清零——end_at 为空，保持 EXECUTING
        when(planMapper.selectCount(any())).thenReturn(0L);

        ExecuteConfirmVO vo = service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));

        // 无任何头迁移（EXECUTING 停留待次日日切继续分解，正常终结路径=停嘱）且 executed 事件照发
        verifyNoInteractions(stateMachine);
        assertThat(vo.orderStatus()).isEqualTo("EXECUTING");
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));

        // 次日日切：该医嘱仍入候选（end_at 为空不越界）——qd 单时点 1 行；end_at 已过医嘱被过滤不排程
        Mockito.reset(planMapper, orderMapper, frequencyMapper, itemMapper, seqGate, events);
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        MedicalOrder expired = orderRow(OrderStatus.EXECUTING, "LONG", false, "bid");
        expired.setId(9002L);
        expired.setOrderNo("MO2026092500002");
        expired.setEndAt(LocalDate.now().minusDays(1).atTime(8, 0).atOffset(offset));
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any())).thenReturn(List.of(order, expired));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("qd", "08:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("PL")).thenReturn("PL2026092600001");

        assertThat(service.decomposeNextDay(tomorrow)).isEqualTo(1);
        verify(planMapper).insert(planCaptor.capture());
        // 唯一生成行归属存活医嘱（end_at 已过者被候选过滤剔除——过滤失效则生成 2 行即失败）
        assertThat(planCaptor.getValue().getOrderId()).isEqualTo(ORDER_PK);
        assertThat(planCaptor.getValue().getPlanTime().toLocalDate()).isEqualTo(tomorrow);
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("跨日窗口守卫（end_at 到期面）：qd 有 end_at=当日，末点回签后全终态→COMPLETED")
    void executeConfirmCompletesQdOrderWhenEndAtReachedToday() {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        MedicalOrder order = orderRow(OrderStatus.EXECUTING, "LONG", false, "qd");
        // 医嘱明示结束日=当日：末点回签后已有计划全终态且生命周期确已穷尽——允许 COMPLETED
        order.setEndAt(LocalDate.now().atTime(23, 59).atOffset(offset));
        doAnswer(invocation -> {
                    invocation
                            .getArgument(0, MedicalOrder.class)
                            .setStatus(
                                    invocation.getArgument(1, OrderStatus.class).getCode());
                    return null;
                })
                .when(stateMachine)
                .transition(any(), any(), any(), anyLong());
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(planMapper.selectCount(any())).thenReturn(0L);

        ExecuteConfirmVO vo = service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));

        verify(stateMachine).transition(order, OrderStatus.COMPLETED, "全部执行计划实例终态", OPERATOR);
        assertThat(vo.orderStatus()).isEqualTo("COMPLETED");
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("用例⑦重复回签幂等：CAS 零行且已 EXECUTED→返回当前状态零迁移零事件；已 CANCELLED 拒 IP-1015")
    void executeConfirmIsIdempotentForExecutedPlanAndRejectsCancelled() {
        // 重复回签：首读 PENDING、CAS 零行、重读 EXECUTED——幂等返回当前状态（不迁移不发事件）
        MedicalOrder completed = orderRow(OrderStatus.COMPLETED, "STAT", false, null);
        OrderExecutePlan executed = pendingPlanRow();
        executed.setStatus(PlanStatus.EXECUTED.getCode());
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow(), executed);
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(0);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(completed);

        ExecuteConfirmVO vo = service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));

        assertThat(vo).isEqualTo(new ExecuteConfirmVO(PLAN_NO, ORDER_NO, "COMPLETED", "EXECUTED"));
        verifyNoInteractions(stateMachine, events, visitMapper);

        // 已作废计划：重读 CANCELLED——IP-1015 拒绝
        OrderExecutePlan cancelled = pendingPlanRow();
        cancelled.setStatus(PlanStatus.CANCELLED.getCode());
        Mockito.reset(planMapper);
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow(), cancelled);
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PLAN_STATE_NOT_ALLOWED));
        verifyNoInteractions(stateMachine, events);
    }

    @Test
    @DisplayName("用例⑧停嘱作废未来计划：复用 Task 7 已落库 cancelFuturePlans 面（STOPPED+值面+停嘱时点后 PENDING 全 CANCELLED）")
    void stopCancelsFuturePendingPlansViaLandedFace() {
        MedicalOrder order = orderRow(OrderStatus.EXECUTING, "LONG", false, "bid");
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(orderMapper.updateStopValues(eq(ORDER_NO), any(), eq("疗程结束"), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(planMapper.cancelFuturePending(eq(ORDER_PK), any(), eq(String.valueOf(OPERATOR))))
                .thenReturn(3);

        // 真实 MedicalOrderServiceImpl（复用 Task 7 已落库面——本测试与计划域共享 mock 协作者）
        MedicalOrderServiceImpl medicalOrderService = new MedicalOrderServiceImpl(
                orderMapper,
                itemMapper,
                frequencyMapper,
                planMapper,
                visitMapper,
                seqGate,
                practiceCheckPort,
                allergyChecker,
                stateMachine,
                events,
                orderAuditService);
        medicalOrderService.stop(ORDER_NO, "疗程结束");

        verify(stateMachine).transition(order, OrderStatus.STOPPED, "疗程结束", OPERATOR);
        // 停嘱时点后的 PENDING 计划批量 CANCELLED（order_id+停嘱时点条件面——操作者审计同源）
        ArgumentCaptor<OffsetDateTime> stoppedAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(planMapper).cancelFuturePending(eq(ORDER_PK), stoppedAt.capture(), eq(String.valueOf(OPERATOR)));
        assertThat(stoppedAt.getValue()).isBeforeOrEqualTo(OffsetDateTime.now());
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("用例⑨trace 聚合：开立/审核（含药师）/转抄/计划执行/状态迁移五环节人/时/果一屏升序")
    void traceAggregatesFiveStagesInTimeOrder() {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        MedicalOrder order = orderRow(OrderStatus.STOPPED, "LONG", false, "bid");
        order.setOrderedAt(LocalDate.now().atTime(8, 0).atOffset(offset));
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        // 审核双轨（SYSTEM 预检 08:01 + PHARMACIST 审方 08:05）
        OrderAudit systemAudit =
                auditRow("SYSTEM", "PASSED", LocalDate.now().atTime(8, 1).atOffset(offset));
        OrderAudit pharmacistAudit =
                auditRow("PHARMACIST", "PASSED", LocalDate.now().atTime(8, 5).atOffset(offset));
        when(auditMapper.selectList(any())).thenReturn(List.of(systemAudit, pharmacistAudit));
        // 转抄（09:00，第二核对人缺席）
        OrderTransferLog transfer = transferRow(LocalDate.now().atTime(9, 0).atOffset(offset));
        when(transferLogMapper.selectList(any())).thenReturn(List.of(transfer));
        // 计划执行（EXECUTED 12:03 + 停嘱联动作废的 16:00 计划——occurredAt 回退计划时点占位）
        OrderExecutePlan executed =
                existingPlan(101L, LocalDate.now().atTime(12, 0).atOffset(offset));
        executed.setPlanNo("PL2026092500101");
        executed.setExecutorId(String.valueOf(EXECUTOR_NURSE));
        executed.setExecutedAt(LocalDate.now().atTime(12, 3).atOffset(offset));
        executed.setStatus(PlanStatus.EXECUTED.getCode());
        OrderExecutePlan cancelled =
                existingPlan(101L, LocalDate.now().atTime(16, 0).atOffset(offset));
        cancelled.setPlanNo("PL2026092500102");
        cancelled.setStatus(PlanStatus.CANCELLED.getCode());
        when(planMapper.selectList(any())).thenReturn(List.of(executed, cancelled));
        // 状态迁移（EXECUTING→STOPPED 14:00）
        OrderStatusLog stopLog = statusLogRow(
                "EXECUTING", "STOPPED", LocalDate.now().atTime(14, 0).atOffset(offset));
        when(statusLogMapper.selectList(any())).thenReturn(List.of(stopLog));

        OrderTraceVO vo = service.trace(ORDER_NO);

        assertThat(vo.orderNo()).isEqualTo(ORDER_NO);
        assertThat(vo.visitId()).isEqualTo(VISIT_ID);
        assertThat(vo.status()).isEqualTo("STOPPED");
        assertThat(vo.orderType()).isEqualTo("DRUG");
        assertThat(vo.orderClass()).isEqualTo("LONG");
        // 五环节齐全（时点升序：开立 08:00→审核 08:01/08:05→转抄 09:00→计划 12:03→停嘱 14:00
        // →作废计划 16:00——未执行行 occurredAt 回退计划时点占位，人/时/果三缺一可辨）
        assertThat(vo.entries())
                .extracting(OrderTraceVO.TraceEntry::stage)
                .containsExactly("ORDERED", "AUDIT", "AUDIT", "TRANSFER", "PLAN", "STATUS", "PLAN");
        // 人/时/果抽锚：开立医生/药师审方操作者/执行护士/停嘱操作者
        assertThat(vo.entries().get(0).operator()).isEqualTo("1001");
        assertThat(vo.entries().get(2).operator()).isEqualTo("8001");
        assertThat(vo.entries().get(4).result()).isEqualTo("EXECUTED");
        assertThat(vo.entries().get(4).detail()).startsWith("PL2026092500101@");
        assertThat(vo.entries().get(5).result()).isEqualTo("STOPPED");
        assertThat(vo.entries().get(5).detail()).contains("EXECUTING→STOPPED");
        assertThat(vo.entries().get(6).result()).isEqualTo("CANCELLED");
        assertThat(vo.entries())
                .isSortedAccordingTo(java.util.Comparator.comparing(OrderTraceVO.TraceEntry::occurredAt));
    }

    @Test
    @DisplayName("分批事务边界：1001 条候选恰 3 批事务（每 500 医嘱一事务）且全量生成")
    void decomposeNextDayCommitsOneTransactionPerFiveHundredOrders() {
        LocalDate planDate = LocalDate.of(2026, 9, 26);
        List<MedicalOrder> orders = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            orders.add(orderRow(OrderStatus.TRANSFERRED, "LONG", false, null));
        }
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any())).thenReturn(orders);
        // 频次编码全部缺失——逐单 warn 跳过零生成（批次事务边界断言不依赖生成面）
        assertThat(service.decomposeNextDay(planDate)).isZero();
        assertThat(begunTransactions.get()).as("1001 条候选应分 3 批（500+500+1）").isEqualTo(3);
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));
    }

    @Test
    @DisplayName("日切候选与跳过面：无在院就诊直过/频次编码缺失/字典无命中/prn 按需/无固定时点各留痕跳过")
    void decomposeNextDayCoversCandidateAndSkipFaces() {
        LocalDate planDate = LocalDate.of(2026, 9, 26);
        // 无在院就诊：零候选直过（零批次事务）
        when(visitMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.decomposeNextDay(planDate)).isZero();
        assertThat(begunTransactions.get()).isZero();

        // 在院就诊四医嘱：频次编码缺失/字典无命中/prn 按需/无固定时点（st 语义）——各自跳过
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        MedicalOrder noFreq = orderRow(OrderStatus.TRANSFERRED, "LONG", false, null);
        MedicalOrder dictMiss = orderRow(OrderStatus.TRANSFERRED, "LONG", false, "q8h");
        MedicalOrder prn = orderRow(OrderStatus.TRANSFERRED, "LONG", true, "prn");
        MedicalOrder stat = orderRow(OrderStatus.EXECUTING, "LONG", false, "st");
        when(orderMapper.selectList(any())).thenReturn(List.of(noFreq, dictMiss, prn, stat));
        when(frequencyMapper.selectOne(any())).thenReturn(null, freqRow("prn", null, true), freqRow("st", null, false));

        assertThat(service.decomposeNextDay(planDate)).isZero();
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("日切生成跳过面：明细缺失/就诊缺失/时点序列解析失败留痕跳过；批次唯一冲突续跑下一批")
    void decomposeNextDayCoversGenerationSkipAndBatchFailureFaces() {
        LocalDate planDate = LocalDate.of(2026, 9, 26);
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        // 明细缺失 + 就诊缺失：warn 跳过零生成
        MedicalOrder noItems = orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid");
        MedicalOrder noVisit = orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid");
        when(orderMapper.selectList(any())).thenReturn(List.of(noItems, noVisit));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bid", "08:00,16:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(), List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThat(service.decomposeNextDay(planDate)).isZero();
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));

        // 时点序列脏数据（非 HH:mm）：解析失败跳过
        Mockito.reset(orderMapper, itemMapper, visitMapper, planMapper);
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any()))
                .thenReturn(List.of(orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bad")));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bad", "08:00,25:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        assertThat(service.decomposeNextDay(planDate)).isZero();
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));

        // 批次唯一冲突（并发窗口）：第一批（首个插入即冲突）整批回滚续跑，第二批正常生成——
        // 断点续跑语义（501 候选分两批：500+1，冲突批不阻断后续批）
        Mockito.reset(orderMapper, itemMapper, visitMapper, planMapper, frequencyMapper, seqGate);
        begunTransactions.set(0);
        LocalDate nextDay = LocalDate.of(2026, 9, 27);
        List<MedicalOrder> orders = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            orders.add(orderRow(OrderStatus.TRANSFERRED, "LONG", false, "qd"));
        }
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        when(orderMapper.selectList(any())).thenReturn(orders);
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("qd", "08:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("PL")).thenReturn("PL2026092700001", "PL2026092700002");
        // 第一批首个插入即唯一冲突（IP-1023 批内传播整批回滚）；第二批单行正常落库
        when(planMapper.insert(any(OrderExecutePlan.class)))
                .thenThrow(new DuplicateKeyException("uk_plan_order_item_time"))
                .thenReturn(1);
        assertThat(service.decomposeNextDay(nextDay)).isEqualTo(1);
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
        assertThat(begunTransactions.get()).as("501 候选分两批（500+1），冲突批后仍续跑").isEqualTo(2);
    }

    @Test
    @DisplayName("补偿守卫面：非长期医嘱/未经转抄状态/操作者上下文缺失回退 system/医嘱不存在降级 warn（IP-1009）")
    void compensateTodayCoversGuardFaces() {
        // 公共入口（服务器时钟形态）直测：STAT 守卫直过零生成——无异常路径承载 public 委托行
        when(orderMapper.selectOne(any())).thenReturn(orderRow(OrderStatus.TRANSFERRED, "STAT", false, null));
        assertThat(service.compensateToday(ORDER_NO)).isZero();

        // 医嘱不存在：IP-1009 降级 warn 不向上传播（审查修复环 R1——补偿面异常不阻断转抄主链）
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThat(service.compensateToday("MISSING", OffsetDateTime.now())).isZero();

        // 非长期医嘱（STAT——临时单次计划由转抄链生成，勿双头生成）：直过零生成
        when(orderMapper.selectOne(any())).thenReturn(orderRow(OrderStatus.TRANSFERRED, "STAT", false, null));
        assertThat(service.compensateToday(ORDER_NO, OffsetDateTime.now())).isZero();

        // 未经转抄（AUDITED）：直过零生成
        when(orderMapper.selectOne(any())).thenReturn(orderRow(OrderStatus.AUDITED, "LONG", false, "bid"));
        assertThat(service.compensateToday(ORDER_NO, OffsetDateTime.now())).isZero();

        // 正常补偿面 + 操作者上下文缺失：审计列回退 system（转抄链防御回退口径）
        OperatorContextHolder.clear();
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        OffsetDateTime at1000 = LocalDate.now().atTime(10, 0).atOffset(offset);
        when(orderMapper.selectOne(any())).thenReturn(orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid"));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bid", "08:00,16:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500099");

        assertThat(service.compensateToday(ORDER_NO, at1000)).isEqualTo(1);
        verify(planMapper).insert(planCaptor.capture());
        // 10:00 补偿仅生成 16:00 剩余时点；审计操作者回退 system
        assertThat(planCaptor.getValue().getPlanTime().toLocalTime().toString()).isEqualTo("16:00");
        assertThat(planCaptor.getValue().getCreatedBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("补偿冲突仅 warn：并发唯一冲突 IP-1023 降级不向上传播（转抄主链成功不受阻断）")
    void compensateTodayDegradesConflictToWarnWithoutBlockingTransferChain() {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        OffsetDateTime at1000 = LocalDate.now().atTime(10, 0).atOffset(offset);
        when(orderMapper.selectOne(any())).thenReturn(orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid"));
        when(frequencyMapper.selectOne(any())).thenReturn(freqRow("bid", "08:00,16:00", false));
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(planMapper.selectList(any())).thenReturn(List.of());
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500098");
        // 并发窗口同项同时点重复生成（转抄链与补偿面竞态）：insertPlan 定性 IP-1023——
        // 修复环 R1 前该异常会传播回滚整批转抄事务，现降级 warn 不再向上抛
        when(planMapper.insert(any(OrderExecutePlan.class)))
                .thenThrow(new DuplicateKeyException("uk_plan_order_item_time"));

        // 补偿面降级：不抛异常（转抄主链事务不因此回滚=主链成功）、返回 0、零事件
        assertThat(service.compensateToday(ORDER_NO, at1000)).isZero();
        verify(planMapper).insert(any(OrderExecutePlan.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("回签守卫面：计划不存在 IP-1014/操作者上下文非数字 IP-1022/医嘱关联缺失 IP-1009/就诊关联缺失 IP-1007")
    void executeConfirmCoversGuardFaces() {
        // 计划不存在：IP-1014
        when(planMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(
                        () -> service.executeConfirm("MISSING", new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.PLAN_NOT_FOUND));

        // 操作者上下文非数字（与请求承载 executorId 并行口径）：IP-1022
        OperatorContextHolder.clear();
        OperatorContextHolder.set("abc");
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        assertThatThrownBy(() -> service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        // 工号脱敏短标识分支（≤2 位）
        OperatorContextHolder.set("x");
        assertThatThrownBy(() -> service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        OperatorContextHolder.set(String.valueOf(OPERATOR));

        // 医嘱关联行缺失：IP-1009（数据不一致 fail-closed）
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 就诊关联行缺失：IP-1007（数据不一致 fail-closed）
        when(orderMapper.selectById(ORDER_PK)).thenReturn(orderRow(OrderStatus.TRANSFERRED, "STAT", false, null));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("首个回签穿透终态判定（单点日计划全回签且 end_at 到期场景）：TRANSFERRED→EXECUTING→COMPLETED 链式迁移")
    void executeConfirmChainsFirstConfirmIntoTerminalAdvance() {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        MedicalOrder order = orderRow(OrderStatus.TRANSFERRED, "LONG", false, "qn");
        // 医嘱明示结束日=当日（审查修复环 R1）：单点频次当日全回签且生命周期穷尽——链式迁移
        order.setEndAt(LocalDate.now().atTime(23, 59).atOffset(offset));
        doAnswer(invocation -> {
                    invocation
                            .getArgument(0, MedicalOrder.class)
                            .setStatus(
                                    invocation.getArgument(1, OrderStatus.class).getCode());
                    return null;
                })
                .when(stateMachine)
                .transition(any(), any(), any(), anyLong());
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(order);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(planMapper.selectCount(any())).thenReturn(0L);

        ExecuteConfirmVO vo = service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));

        verify(stateMachine).transition(order, OrderStatus.EXECUTING, "长期医嘱首个执行回签", OPERATOR);
        verify(stateMachine).transition(order, OrderStatus.COMPLETED, "全部执行计划实例终态", OPERATOR);
        assertThat(vo.orderStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("终态/他态医嘱头无推进面：STAT 已 COMPLETED 后续明细回签与 LONG 已 STOPPED 计划回签仅落计划面仍发事件")
    void executeConfirmSkipsHeadAdvanceForTerminalOrderHeads() {
        // 临时医嘱多明细后续回签：头已 COMPLETED——仅落计划面仍发 executed 事件（M13 费用确认）
        when(planMapper.selectOne(any())).thenReturn(pendingPlanRow());
        when(planMapper.casExecuteConfirm(eq(PLAN_NO), any(), any(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectById(ORDER_PK)).thenReturn(orderRow(OrderStatus.COMPLETED, "STAT", false, null));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        ExecuteConfirmVO statVo =
                service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));
        assertThat(statVo.orderStatus()).isEqualTo("COMPLETED");

        // 长期医嘱已停嘱（终态无出边）：计划面残留 PENDING 回签仅落计划面仍发事件
        when(orderMapper.selectById(ORDER_PK)).thenReturn(orderRow(OrderStatus.STOPPED, "LONG", false, "bid"));
        ExecuteConfirmVO stoppedVo =
                service.executeConfirm(PLAN_NO, new ExecuteConfirmRequest(EXECUTOR_NURSE, null, null));
        assertThat(stoppedVo.orderStatus()).isEqualTo("STOPPED");

        verifyNoInteractions(stateMachine);
        verify(events, times(2)).publishEvent(any(InpatientDomainEvent.class));
    }

    @Test
    @DisplayName("trace 守卫面：医嘱不存在 IP-1009/就诊关联缺失 IP-1007")
    void traceCoversGuardFaces() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.trace("MISSING"))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        when(orderMapper.selectOne(any())).thenReturn(orderRow(OrderStatus.TRANSFERRED, "LONG", false, "bid"));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.trace(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("GC26 锚：执行回签 CAS 为 @Update 注解 SQL 且 PENDING 限定+三引用列落值+deleted=0")
    void casExecuteConfirmIsAnnotatedSqlWithPendingGuard() {
        assertThat(updateSql(
                        "casExecuteConfirm",
                        String.class,
                        String.class,
                        OffsetDateTime.class,
                        String.class,
                        String.class))
                .contains("SET status = 'EXECUTED'")
                .contains("executor_id = #{executorId}")
                .contains("executed_at = #{executedAt}")
                .contains("route_check_result = #{routeCheckResult}")
                .contains("plan_no = #{planNo}")
                .contains("status = 'PENDING'")
                .contains("deleted = 0");
    }

    /** 构造在院就诊行（ADMITTED——日切候选与计划落值载体）。 */
    private InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setCurrentWardId(WARD);
        row.setStatus(VisitStatus.ADMITTED.getCode());
        return row;
    }

    /** 构造医嘱行（状态/分类/嘱托/频次可变——日切候选与回签载体）。 */
    private MedicalOrder orderRow(OrderStatus status, String orderClass, boolean standby, String freqCode) {
        MedicalOrder row = new MedicalOrder();
        row.setId(ORDER_PK);
        row.setOrderNo(ORDER_NO);
        row.setVisitId(VISIT_PK);
        row.setPatientId(PATIENT_ID);
        row.setOrderType("DRUG");
        row.setOrderClass(orderClass);
        row.setStandbyFlag(standby);
        row.setGroupNo(ORDER_NO);
        row.setFreqCode(freqCode);
        row.setDoctorId("1001");
        row.setOrderedAt(OffsetDateTime.now());
        row.setStatus(status.getCode());
        return row;
    }

    /** 构造医嘱明细行（计划按明细行粒度开立——id 为 order_item_id 载体）。 */
    private MedicalOrderItem itemRow(long id) {
        MedicalOrderItem row = new MedicalOrderItem();
        row.setId(id);
        row.setItemSeq(1);
        return row;
    }

    /** 构造频次字典行（时点序列/prn 标记可变——V904 七行种子形态对齐）。 */
    private OrderFrequency freqRow(String freqCode, String timePoints, boolean prnFlag) {
        OrderFrequency row = new OrderFrequency();
        row.setFreqCode(freqCode);
        row.setTimePoints(timePoints);
        row.setPrnFlag(prnFlag);
        return row;
    }

    /** 构造待执行计划行（回签载体：PENDING 初始态）。 */
    private OrderExecutePlan pendingPlanRow() {
        OrderExecutePlan row = new OrderExecutePlan();
        row.setPlanNo(PLAN_NO);
        row.setOrderId(ORDER_PK);
        row.setOrderItemId(101L);
        row.setVisitId(VISIT_PK);
        row.setWardId(WARD);
        row.setPlanTime(OffsetDateTime.now().plusMinutes(30));
        row.setShift("DAY");
        row.setStatus(PlanStatus.PENDING.getCode());
        return row;
    }

    /** 构造目标日已有计划行（查前置幂等断言载体）。 */
    private OrderExecutePlan existingPlan(long itemId, OffsetDateTime planTime) {
        OrderExecutePlan row = new OrderExecutePlan();
        row.setPlanNo("PL-EXISTING");
        row.setOrderId(ORDER_PK);
        row.setOrderItemId(itemId);
        row.setVisitId(VISIT_PK);
        row.setWardId(WARD);
        row.setPlanTime(planTime);
        row.setShift("DAY");
        row.setStatus(PlanStatus.PENDING.getCode());
        return row;
    }

    /** 构造审核流水行（追溯审核环节载体——SYSTEM/PHARMACIST 双轨）。 */
    private OrderAudit auditRow(String stage, String conclusion, OffsetDateTime occurredAt) {
        OrderAudit row = new OrderAudit();
        row.setOrderId(ORDER_PK);
        row.setStage(stage);
        row.setConclusion(conclusion);
        row.setAuditOperator("8001");
        row.setOccurredAt(occurredAt);
        return row;
    }

    /** 构造转抄台账行（追溯转抄环节载体）。 */
    private OrderTransferLog transferRow(OffsetDateTime transferredAt) {
        OrderTransferLog row = new OrderTransferLog();
        row.setOrderId(ORDER_PK);
        row.setTransferNurse("3001");
        row.setTransferredAt(transferredAt);
        row.setConclusion(CheckConclusion.PASSED.getCode());
        return row;
    }

    /** 构造状态迁移留痕行（追溯状态环节载体——含停止）。 */
    private OrderStatusLog statusLogRow(String from, String to, OffsetDateTime occurredAt) {
        OrderStatusLog row = new OrderStatusLog();
        row.setOrderId(ORDER_PK);
        row.setFromStatus(from);
        row.setToStatus(to);
        row.setReason("疗程结束");
        row.setOperator(String.valueOf(OPERATOR));
        row.setOccurredAt(occurredAt);
        return row;
    }

    /**
     * 直读 mapper 方法 @Update 注解 SQL（GC26 可执行锚：条件更新必须为注解 SQL 承载）。
     *
     * @param method     mapper 方法名
     * @param paramTypes 方法参数类型（重载定位）
     * @return 拼接后的注解 SQL 全文
     */
    private String updateSql(String method, Class<?>... paramTypes) {
        try {
            Method target = OrderExecutePlanMapper.class.getMethod(method, paramTypes);
            Update update = target.getAnnotation(Update.class);
            assertThat(update).as("条件更新必须为 @Update 注解 SQL（GC26）").isNotNull();
            return String.join("", update.value());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("mapper 方法不存在：" + method, e);
        }
    }
}
