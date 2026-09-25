package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderExecutePlanMapper;
import com.fuyun.inpatient.mapper.OrderTransferLogMapper;
import com.fuyun.inpatient.properties.InpatientProperties;
import com.fuyun.inpatient.service.OrderPlanService;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.OrderPlanVO;
import com.fuyun.inpatient.vo.TransferWorklistVO;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.http.HttpStatus;

/**
 * 医嘱转抄与执行计划域服务单测（Task 7 冻结五用例 + 覆盖率补面）：①批量转抄（两条临时医嘱
 * →TRANSFERRED+两事件+单次计划生成）②输血类缺第二核对人拦截 IP-1016（携带面放行对照）
 * ③嘱托两次触发两计划 plan_no 各异④重复转抄幂等跳过⑤worklist 病区过滤（班次窗口/守卫/
 * 空集补面）；守卫面（结论 REJECTED/护士缺失/操作者非数字/医嘱缺失/状态/词表脏数据/就诊
 * 关联缺失）、计划查询批量号映射与关联缺失面、嘱托守卫面与并发重复生成、转科三分钩子
 * （长期作废+临时重定向+空集直过）、长期医嘱转抄补偿衔接（Task 8：compensateToday 调用面）、
 * currentShift 三窗口边界直测、GC26 条件更新注解 SQL 锚。
 * MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息；状态机按冻结接口
 * mock（状态面语义由 OrderStateMachineServiceImplTest 承载）。
 */
@ExtendWith(MockitoExtension.class)
class OrderTransferServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行主键（order_execute_plan.visit_id 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 操作者员工 ID（数字形态——转抄操作与审计口径） */
    private static final long OPERATOR = 2001L;

    /** 在院病区（计划落值与 worklist 过滤键） */
    private static final String WARD = "W01";

    @Mock
    private MedicalOrderMapper orderMapper;

    @Mock
    private MedicalOrderItemMapper itemMapper;

    @Mock
    private OrderTransferLogMapper transferLogMapper;

    @Mock
    private OrderExecutePlanMapper planMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private InpatientSeqGate seqGate;

    @Mock
    private OrderStateMachineService stateMachine;

    @Mock
    private OrderPlanService orderPlanService;

    /** 住院域参数（默认值实例——准备窗口 60 分钟/欠费阈值 0/随访 14 日，与应用缺省同源） */
    private final InpatientProperties properties = new InpatientProperties(0L, 14, 60);

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<OrderTransferLog> logCaptor;

    @Captor
    private ArgumentCaptor<OrderExecutePlan> planCaptor;

    private OrderTransferServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（四实体查询面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrderItem.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderExecutePlan.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderTransferServiceImpl(
                orderMapper,
                itemMapper,
                transferLogMapper,
                planMapper,
                visitMapper,
                seqGate,
                stateMachine,
                orderPlanService,
                properties,
                events);
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("用例①批量转抄：两条临时医嘱→两状态机迁移+两台账行+两 transferred 事件（id 42 载荷）+两单次计划（plan_no 各异）")
    void transferCheckBatchTransfersStatOrdersWithEventsAndSinglePlans() {
        OffsetDateTime before = OffsetDateTime.now();
        MedicalOrder first = orderRow("MO2026092500001", 9001L, OrderStatus.AUDITED, "STAT", "LAB", false);
        MedicalOrder second = orderRow("MO2026092500002", 9002L, OrderStatus.AUDITED, "STAT", "DRUG", false);
        when(orderMapper.selectOne(any())).thenReturn(first, second);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)), List.of(itemRow(102L)));
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500001", "PL2026092500002");

        service.transferCheck(new TransferCheckRequest(
                List.of("MO2026092500001", "MO2026092500002"), "3001", CheckConclusion.PASSED, null));

        // 两条状态机迁移（AUDITED→TRANSFERRED 唯一经状态机——留痕随状态机自动落库）
        verify(stateMachine).transition(first, OrderStatus.TRANSFERRED, "护士转抄核对", OPERATOR);
        verify(stateMachine).transition(second, OrderStatus.TRANSFERRED, "护士转抄核对", OPERATOR);

        // 两事件（V800 id 42 载荷逐字：m04OrderNo/visitId/patientId/transferType/firstTransferredAt；
        // transferType=医嘱类型子键小写形态——登记名不带子键故类型入载荷）
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        List<OrderTransferredPayload> payloads = eventCaptor.getAllValues().stream()
                .map(InpatientDomainEvent::payload)
                .map(OrderTransferredPayload.class::cast)
                .toList();
        assertThat(eventCaptor.getAllValues()).allSatisfy(event -> assertThat(event.eventType())
                .isEqualTo(InpatientMessagingConstants.EVENT_ORDER_TRANSFERRED));
        assertThat(payloads)
                .extracting(OrderTransferredPayload::m04OrderNo)
                .containsExactly("MO2026092500001", "MO2026092500002");
        assertThat(payloads).extracting(OrderTransferredPayload::transferType).containsExactly("lab", "drug");
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload.visitId()).isEqualTo(VISIT_ID);
            assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
            assertThat(payload.firstTransferredAt()).isAfter(before.toInstant());
        });

        // 两台账行（双人核对留痕四要素：转抄护士/时点/结论/第二核对人——普通医嘱第二核对人空）
        verify(transferLogMapper, times(2)).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .extracting(OrderTransferLog::getOrderId)
                .containsExactly(9001L, 9002L);
        assertThat(logCaptor.getAllValues()).allSatisfy(row -> {
            assertThat(row.getTransferNurse()).isEqualTo("3001");
            assertThat(row.getConclusion()).isEqualTo("PASSED");
            assertThat(row.getSecondCheckerId()).isNull();
            assertThat(row.getTransferredAt()).isAfter(before);
        });

        // 两单次计划（临时医嘱转抄同步生成：plan_no 各异/PENDING/明细行粒度/病区/计划时点=转抄+准备窗口）
        verify(planMapper, times(2)).insert(planCaptor.capture());
        assertThat(planCaptor.getAllValues())
                .extracting(OrderExecutePlan::getPlanNo)
                .containsExactly("PL2026092500001", "PL2026092500002");
        assertThat(planCaptor.getAllValues()).allSatisfy(plan -> {
            assertThat(plan.getVisitId()).isEqualTo(VISIT_PK);
            assertThat(plan.getWardId()).isEqualTo(WARD);
            assertThat(plan.getStatus()).isEqualTo("PENDING");
            assertThat(plan.getShift()).isIn("DAY", "EVENING", "NIGHT");
            // 默认准备窗口：计划时点在转抄时点之后（60 分钟缓冲——Task 10 回接 InpatientProperties
            // .defaultExecuteWindowMinutes 缺省，原 Task 7 常量 15 分钟，行为变更随 2026-09-25 报告留痕；
            // 留 1 分钟容差断言）
            assertThat(plan.getPlanTime()).isAfter(before.plusMinutes(59));
        });
    }

    @Test
    @DisplayName("用例②输血类缺第二核对人：IP-1016 拦截零触达；携带第二核对人放行且台账落签名")
    void bloodOrderWithoutSecondCheckerRejectedAndWithCheckerPasses() {
        MedicalOrder blood = orderRow("MO2026092500003", 9003L, OrderStatus.AUDITED, "STAT", "BLOOD", false);
        when(orderMapper.selectOne(any())).thenReturn(blood);

        // 缺第二核对人（高危/输血强制——调研依据 5）：IP-1016，状态机/台账/事件/计划零触达
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO2026092500003"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.TRANSFER_CHECK_INVALID);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(stateMachine, transferLogMapper, events, planMapper);

        // 携带第二核对人：放行且台账 second_checker_id 落签名（双人核对留痕收口）
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(103L)));
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500003");
        service.transferCheck(
                new TransferCheckRequest(List.of("MO2026092500003"), "3001", CheckConclusion.PASSED, "3002"));
        verify(stateMachine).transition(blood, OrderStatus.TRANSFERRED, "护士转抄核对", OPERATOR);
        verify(transferLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getSecondCheckerId()).isEqualTo("3002");
        assertThat(logCaptor.getValue().getConclusion()).isEqualTo("PASSED");
        // 事件 transferType=输血类子键 blood
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(((OrderTransferredPayload) eventCaptor.getValue().payload()).transferType())
                .isEqualTo("blood");
    }

    @Test
    @DisplayName("用例③嘱托触发：两次触发两计划（plan_no 各异）且医嘱头不迁移；出参携带计划号/状态 PENDING")
    void standbyTriggerTwiceCreatesDistinctPlansWithoutOrderTransition() {
        MedicalOrder standby = orderRow("MO2026092500004", 9004L, OrderStatus.TRANSFERRED, "LONG", "DRUG", true);
        when(orderMapper.selectOne(any())).thenReturn(standby);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(104L)));
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500004", "PL2026092500005");

        List<OrderPlanVO> firstCall = service.standbyTrigger("MO2026092500004");
        List<OrderPlanVO> secondCall = service.standbyTrigger("MO2026092500004");

        // 两次触发两次台账（每次触发独立计划实例——M13 唯一键兜底不重复计价的计划侧对偶面）
        assertThat(firstCall).hasSize(1);
        assertThat(secondCall).hasSize(1);
        assertThat(firstCall.get(0).planNo()).isEqualTo("PL2026092500004");
        assertThat(secondCall.get(0).planNo()).isEqualTo("PL2026092500005");
        assertThat(firstCall.get(0).status()).isEqualTo("PENDING");
        assertThat(firstCall.get(0).orderNo()).isEqualTo("MO2026092500004");
        assertThat(firstCall.get(0).visitId()).isEqualTo(VISIT_ID);
        verify(planMapper, times(2)).insert(planCaptor.capture());
        assertThat(planCaptor.getAllValues())
                .extracting(OrderExecutePlan::getPlanNo)
                .containsExactly("PL2026092500004", "PL2026092500005");
        // 医嘱头状态不迁移（回签面推进——W-33 契约归 Task 8）
        verifyNoInteractions(stateMachine, events);
    }

    @Test
    @DisplayName("用例④重复转抄幂等：已 TRANSFERRED 零副作用跳过，批内待转抄医嘱正常转抄")
    void transferCheckSkipsAlreadyTransferredIdempotently() {
        MedicalOrder done = orderRow("MO2026092500005", 9005L, OrderStatus.TRANSFERRED, "STAT", "DRUG", false);
        MedicalOrder pending = orderRow("MO2026092500006", 9006L, OrderStatus.AUDITED, "STAT", "DRUG", false);
        when(orderMapper.selectOne(any())).thenReturn(done, pending);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(106L)));
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500006");

        service.transferCheck(new TransferCheckRequest(
                List.of("MO2026092500005", "MO2026092500006"), "3001", CheckConclusion.PASSED, null));

        // 已 TRANSFERRED 医嘱零触达（无迁移/无台账/无事件/无计划）；批内 AUDITED 医嘱正常转抄
        verify(stateMachine, never()).transition(eq(done), any(), any(), any());
        verify(stateMachine).transition(pending, OrderStatus.TRANSFERRED, "护士转抄核对", OPERATOR);
        verify(transferLogMapper, times(1)).insert(any(OrderTransferLog.class));
        verify(events, times(1)).publishEvent(any(InpatientDomainEvent.class));
        verify(planMapper, times(1)).insert(any(OrderExecutePlan.class));
    }

    @Test
    @DisplayName("用例⑤worklist 病区过滤：在院就诊聚合 AUDITED 医嘱+就诊号映射；班次窗口/守卫/空集补面")
    void worklistFiltersByWardWithShiftWindowAndGuards() {
        // 班次词表外：IP-1022（Web 层 @Pattern 兜底，服务面覆盖模块内直调场景）
        assertThatThrownBy(() -> service.worklist(WARD, "MIDDAY", 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        // 病区缺失：IP-1022
        assertThatThrownBy(() -> service.worklist(" ", null, 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 病区无在院就诊：空集直过（无待转抄面）
        when(visitMapper.selectList(any())).thenReturn(List.of());
        PageResult<TransferWorklistVO> empty = service.worklist(WARD, null, 0, 20);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.total()).isZero();

        // 病区聚合：在院就诊的 AUDITED 医嘱（开立时间倒序由 orderByDesc 承载）+就诊号映射
        when(visitMapper.selectList(any())).thenReturn(List.of(visitRow()));
        MedicalOrder audited = orderRow("MO2026092500007", 9007L, OrderStatus.AUDITED, "STAT", "DRUG", false);
        audited.setOrderedAt(
                LocalDate.now().atTime(10, 0).atOffset(OffsetDateTime.now().getOffset()));
        Page<MedicalOrder> page = new Page<>(1, 20);
        page.setRecords(List.of(audited));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        PageResult<TransferWorklistVO> result = service.worklist(WARD, null, 0, 20);
        assertThat(result.total()).isEqualTo(1);
        TransferWorklistVO row = result.content().get(0);
        assertThat(row.orderNo()).isEqualTo("MO2026092500007");
        assertThat(row.visitId()).isEqualTo(VISIT_ID);
        assertThat(row.highRisk()).isFalse();

        // 班次窗口：白班窗口命中（开立 10:00 落白班）；大夜班窗口空集（00:00–08:00 外）
        when(orderMapper.selectPage(any(), any())).thenReturn(page, emptyPage());
        assertThat(service.worklist(WARD, "DAY", 0, 20).content()).hasSize(1);
        assertThat(service.worklist(WARD, "NIGHT", 0, 20).content()).isEmpty();

        // 输血类医嘱 highRisk=true（双人核对强制面提示）
        MedicalOrder blood = orderRow("MO2026092500008", 9008L, OrderStatus.AUDITED, "STAT", "BLOOD", false);
        blood.setOrderedAt(
                LocalDate.now().atTime(17, 0).atOffset(OffsetDateTime.now().getOffset()));
        Page<MedicalOrder> bloodPage = new Page<>(1, 20);
        bloodPage.setRecords(List.of(blood));
        bloodPage.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(bloodPage);
        assertThat(service.worklist(WARD, "EVENING", 0, 20).content().get(0).highRisk())
                .isTrue();
    }

    @Test
    @DisplayName(
            "转抄守卫面：结论 REJECTED IP-1016/护士缺失 IP-1022/操作者非数字 IP-1022/医嘱缺失 IP-1009/状态 IP-1010/词表脏数据 IP-1023/就诊关联缺失 IP-1007；长期医嘱转抄不生成计划")
    void transferCheckCoversGuardFacesAndLongOrderSkipsPlans() {
        // 核对结论 REJECTED（核对不符临床流程退回医生站）：IP-1016
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "3001", CheckConclusion.REJECTED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.TRANSFER_CHECK_INVALID));

        // 转抄护士缺失：IP-1022
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 操作者标识非数字（长标识脱敏首尾留位分支）：IP-1022
        OperatorContextHolder.set("nurse-01");
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 操作者上下文缺失（脱敏空标识分支——机器调用未注入操作者场景）：IP-1022
        OperatorContextHolder.clear();
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        OperatorContextHolder.set(String.valueOf(OPERATOR));

        // 医嘱不存在：IP-1009
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO404"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 状态不经转抄（CREATED 停留待审）：IP-1010，状态机/台账/事件零触达
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO1", 9001L, OrderStatus.CREATED, "STAT", "DRUG", false));
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        verifyNoInteractions(stateMachine, transferLogMapper, events);

        // 医嘱类型词表外脏数据（fail-closed）：IP-1023
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO1", 9001L, OrderStatus.AUDITED, "STAT", "PHYSIO", false));
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));

        // 就诊关联行缺失（数据不一致）：IP-1007（迁移前置取数面）
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO1", 9001L, OrderStatus.AUDITED, "STAT", "DRUG", false));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.transferCheck(
                        new TransferCheckRequest(List.of("MO1"), "3001", CheckConclusion.PASSED, null)))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
        verifyNoInteractions(stateMachine, transferLogMapper, events);

        // 长期医嘱转抄：迁移+台账+事件正常，不生成单次计划——计划面走当日补偿衔接（Task 8：
        // compensateToday 承载剩余时点补生成，与临时单次计划生成点互斥勿双头生成）
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO2", 9002L, OrderStatus.AUDITED, "LONG", "DRUG", false));
        service.transferCheck(new TransferCheckRequest(List.of("MO2"), "3001", CheckConclusion.PASSED, null));
        verify(stateMachine).transition(any(MedicalOrder.class), eq(OrderStatus.TRANSFERRED), any(), eq(OPERATOR));
        verify(transferLogMapper).insert(any(OrderTransferLog.class));
        verify(events).publishEvent(any(InpatientDomainEvent.class));
        verify(orderPlanService).compensateToday("MO2");
        verify(itemMapper, never()).selectList(any());
        verify(planMapper, never()).insert(any(OrderExecutePlan.class));
    }

    @Test
    @DisplayName("计划查询面：日期缺失 IP-1022/空页直过/批量号映射出参；关联医嘱缺失 IP-1009/关联就诊缺失 IP-1007")
    void listPlansCoversMappingAndMissingAssociationFaces() {
        // 日期缺失（计划视图以日为轴）：IP-1022
        assertThatThrownBy(() -> service.listPlans(null, WARD, 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 当日无计划：空页直过
        when(planMapper.selectPage(any(), any())).thenReturn(emptyPlanPage());
        PageResult<OrderPlanVO> empty = service.listPlans(LocalDate.now(), null, 0, 20);
        assertThat(empty.content()).isEmpty();

        // 批量号映射：计划行→医嘱号/就诊号两跳批量查询承载（免行级 N+1）
        when(planMapper.selectPage(any(), any())).thenReturn(planPage(planRow("PL2026092500001", 9001L)));
        when(orderMapper.selectBatchIds(List.of(9001L)))
                .thenReturn(
                        List.of(orderRow("MO2026092500009", 9001L, OrderStatus.TRANSFERRED, "STAT", "DRUG", false)));
        when(visitMapper.selectBatchIds(List.of(VISIT_PK))).thenReturn(List.of(visitRow()));
        PageResult<OrderPlanVO> result = service.listPlans(LocalDate.now(), WARD, 0, 20);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).planNo()).isEqualTo("PL2026092500001");
        assertThat(result.content().get(0).orderNo()).isEqualTo("MO2026092500009");
        assertThat(result.content().get(0).visitId()).isEqualTo(VISIT_ID);

        // 关联医嘱缺失（数据不一致）：IP-1009
        when(orderMapper.selectBatchIds(List.of(9001L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.listPlans(LocalDate.now(), WARD, 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 关联就诊缺失（数据不一致）：IP-1007
        when(orderMapper.selectBatchIds(List.of(9001L)))
                .thenReturn(
                        List.of(orderRow("MO2026092500009", 9001L, OrderStatus.TRANSFERRED, "STAT", "DRUG", false)));
        when(visitMapper.selectBatchIds(List.of(VISIT_PK))).thenReturn(List.of());
        assertThatThrownBy(() -> service.listPlans(LocalDate.now(), WARD, 0, 20))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("嘱托守卫面：医嘱缺失 IP-1009/非嘱托 IP-1022/未转抄 IP-1010/就诊缺失 IP-1007/并发重复生成 IP-1023")
    void standbyTriggerCoversGuardFaces() {
        // 医嘱不存在：IP-1009
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.standbyTrigger("MO404"))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 非嘱托医嘱（standby_flag=false——开立 L4 守卫的对偶消费面）：IP-1022
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO1", 9001L, OrderStatus.TRANSFERRED, "LONG", "DRUG", false));
        assertThatThrownBy(() -> service.standbyTrigger("MO1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 未转抄（AUDITED——转抄前置未完成禁触发计划）：IP-1010
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO1", 9001L, OrderStatus.AUDITED, "LONG", "DRUG", true));
        assertThatThrownBy(() -> service.standbyTrigger("MO1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.ORDER_STATE_NOT_ALLOWED));

        // 就诊关联行缺失：IP-1007
        when(orderMapper.selectOne(any()))
                .thenReturn(orderRow("MO1", 9001L, OrderStatus.TRANSFERRED, "LONG", "DRUG", true));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.standbyTrigger("MO1"))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // 并发重复生成（uk_plan_order_item_time 冲突——同项同时点）：IP-1023
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        when(itemMapper.selectList(any())).thenReturn(List.of(itemRow(101L)));
        when(seqGate.nextNo("PL")).thenReturn("PL2026092500001");
        when(planMapper.insert(any(OrderExecutePlan.class)))
                .thenThrow(new DuplicateKeyException("uk_plan_order_item_time"));
        assertThatThrownBy(() -> service.standbyTrigger("MO1"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("转科三分钩子：长期医嘱计划批量作废+临时医嘱计划病区重定向；空集直过零计划触达")
    void redirectPlansOnWardTransferCancelsLongAndRedirectsStat() {
        MedicalOrder longOrder = orderRow("MO1", 9001L, OrderStatus.STOPPED, "LONG", "DRUG", false);
        MedicalOrder statOrder = orderRow("MO2", 9002L, OrderStatus.TRANSFERRED, "STAT", "DRUG", false);
        // 分野查询序：先 LONG（作废面）后 STAT（重定向面）
        when(orderMapper.selectList(any())).thenReturn(List.of(longOrder), List.of(statOrder));

        service.redirectPlansOnWardTransfer(VISIT_PK, "W02", "doc-01");

        // 长期 PENDING 计划批量作废 + 临时 PENDING 计划重定向目标病区（各一条条件更新）
        verify(planMapper).cancelPendingByOrderIds(List.of(9001L), "doc-01");
        verify(planMapper).redirectWardByOrderIds(List.of(9002L), "W02", "doc-01");

        // 空集直过：两类医嘱均无在途行时零计划条件更新（转科编排不受阻断）
        Mockito.reset(orderMapper, planMapper);
        when(orderMapper.selectList(any())).thenReturn(List.of(), List.of());
        service.redirectPlansOnWardTransfer(VISIT_PK, "W02", "doc-01");
        verifyNoInteractions(planMapper);
    }

    @Test
    @DisplayName("时点落班三窗口边界：00:00–08:00 大夜/08:00–16:00 白班/16:00–24:00 小夜（左闭右开）")
    void currentShiftCoversThreeWindowBoundaries() {
        OffsetDateTime base =
                LocalDate.now().atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
        assertThat(OrderTransferServiceImpl.currentShift(base.plusHours(0))).isEqualTo("NIGHT");
        assertThat(OrderTransferServiceImpl.currentShift(base.plusHours(7).plusMinutes(59)))
                .isEqualTo("NIGHT");
        assertThat(OrderTransferServiceImpl.currentShift(base.plusHours(8))).isEqualTo("DAY");
        assertThat(OrderTransferServiceImpl.currentShift(base.plusHours(15).plusMinutes(59)))
                .isEqualTo("DAY");
        assertThat(OrderTransferServiceImpl.currentShift(base.plusHours(16))).isEqualTo("EVENING");
        assertThat(OrderTransferServiceImpl.currentShift(base.plusHours(23).plusMinutes(59)))
                .isEqualTo("EVENING");
    }

    @Test
    @DisplayName("GC26 锚：计划作废/重定向条件更新为 @Update 注解 SQL 且显式 deleted=0 与 PENDING 限定")
    void planMapperConditionalUpdatesAreAnnotatedSqlWithDeletedGuard() {
        assertThat(updateSql("cancelFuturePending", Long.class, OffsetDateTime.class, String.class))
                .contains("SET status = 'CANCELLED'")
                .contains("plan_time > #{stoppedAt}")
                .contains("status = 'PENDING'")
                .contains("deleted = 0");
        assertThat(updateSql("cancelPendingByOrderIds", List.class, String.class))
                .contains("SET status = 'CANCELLED'")
                .contains("status = 'PENDING'")
                .contains("deleted = 0");
        assertThat(updateSql("redirectWardByOrderIds", List.class, String.class, String.class))
                .contains("SET ward_id = #{toWardId}")
                .contains("status = 'PENDING'")
                .contains("deleted = 0");
    }

    /** 构造在院就诊行（ADMITTED——worklist 聚合与计划落值载体）。 */
    private InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setCurrentWardId(WARD);
        row.setStatus(VisitStatus.ADMITTED.getCode());
        return row;
    }

    /** 构造医嘱行（分类/类型/嘱托/状态可变——转抄与计划载体）。 */
    private MedicalOrder orderRow(
            String orderNo, long id, OrderStatus status, String orderClass, String orderType, boolean standby) {
        MedicalOrder row = new MedicalOrder();
        row.setId(id);
        row.setOrderNo(orderNo);
        row.setVisitId(VISIT_PK);
        row.setPatientId(PATIENT_ID);
        row.setOrderType(orderType);
        row.setOrderClass(orderClass);
        row.setStandbyFlag(standby);
        row.setGroupNo(orderNo);
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

    /** 构造执行计划行（查询出参映射载体）。 */
    private OrderExecutePlan planRow(String planNo, long orderId) {
        OrderExecutePlan row = new OrderExecutePlan();
        row.setPlanNo(planNo);
        row.setOrderId(orderId);
        row.setOrderItemId(101L);
        row.setVisitId(VISIT_PK);
        row.setWardId(WARD);
        row.setPlanTime(OffsetDateTime.now().plusMinutes(15));
        row.setShift("DAY");
        row.setStatus("PENDING");
        return row;
    }

    /** 构造空医嘱分页（worklist 空窗口载体）。 */
    private Page<MedicalOrder> emptyPage() {
        Page<MedicalOrder> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        return page;
    }

    /** 构造空计划分页（计划查询空页载体）。 */
    private Page<OrderExecutePlan> emptyPlanPage() {
        Page<OrderExecutePlan> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        return page;
    }

    /** 构造单行计划分页（计划查询映射载体）。 */
    private Page<OrderExecutePlan> planPage(OrderExecutePlan row) {
        Page<OrderExecutePlan> page = new Page<>(1, 20);
        page.setRecords(List.of(row));
        page.setTotal(1);
        return page;
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
