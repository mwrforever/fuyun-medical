package com.fuyun.outpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.OutpatientBillingPort;
import com.fuyun.billing.api.VisitFeeView;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.outpatient.api.OrderCreatedPayload;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.constants.OutpatientMessagingConstants;
import com.fuyun.outpatient.dto.OrderCreateRequest;
import com.fuyun.outpatient.dto.OrderItemRequest;
import com.fuyun.outpatient.entity.ClinicOrder;
import com.fuyun.outpatient.entity.ClinicOrderItem;
import com.fuyun.outpatient.entity.Visit;
import com.fuyun.outpatient.entity.VisitStatusLog;
import com.fuyun.outpatient.enums.OrderStatus;
import com.fuyun.outpatient.enums.OrderType;
import com.fuyun.outpatient.enums.VisitStatus;
import com.fuyun.outpatient.internal.OutpatientDomainEvent;
import com.fuyun.outpatient.mapper.ClinicOrderItemMapper;
import com.fuyun.outpatient.mapper.ClinicOrderMapper;
import com.fuyun.outpatient.mapper.VisitMapper;
import com.fuyun.outpatient.mapper.VisitStatusLogMapper;
import com.fuyun.outpatient.service.IClinicOrderService;
import com.fuyun.outpatient.vo.ClinicOrderVO;
import com.fuyun.system.api.PracticeCheckPort;
import com.fuyun.system.api.PracticeCheckResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

/**
 * 门诊医生站开单服务单测（M03 FU-M03-05，Task 8 冻结用例集 6 例 + 守卫用例）：开单发布
 * order.created（lines 逐字断言）、执业授权强校验 OP-1017、单号连续签发、作废 PENDING 费用行
 * 经端口、CHARGED/RX_REF 引导性拒绝 OP-1015、visit 终态开单拒绝（红线 5）；守卫用例承载
 * OP-1001/OP-1014/OP-1019 与缴费回执推进（幂等跳过/竞态跳过/visit 推进）分支行覆盖
 * （service.impl 包 LINE=1.00 门禁）。端口异常转译（W-20 关联面）以业务拒绝透传与底层异常转译两用例锚定。
 */
@ExtendWith(MockitoExtension.class)
class ClinicOrderServiceImplTest {

    /** 单号日期段格式（与主类同源：OP+yyyyMMdd+6 位流水） */
    private static final DateTimeFormatter SEQ_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    @Mock
    private ClinicOrderMapper clinicOrderMapper;

    @Mock
    private ClinicOrderItemMapper clinicOrderItemMapper;

    @Mock
    private VisitMapper visitMapper;

    @Mock
    private VisitStatusLogMapper visitStatusLogMapper;

    @Mock
    private PracticeCheckPort practiceCheckPort;

    @Mock
    private OutpatientBillingPort billingPort;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<OutpatientDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<ClinicOrder> orderCaptor;

    @Captor
    private ArgumentCaptor<VisitStatusLog> statusLogCaptor;

    private IClinicOrderService service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件列解析依赖 TableInfo（容器外单测手动初始化一次：申请单/明细/visit 三实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ClinicOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ClinicOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Visit.class);
    }

    @BeforeEach
    void setUp() {
        service = new ClinicOrderServiceImpl(
                clinicOrderMapper,
                clinicOrderItemMapper,
                visitMapper,
                visitStatusLogMapper,
                practiceCheckPort,
                billingPort,
                redisTemplate,
                events);
        // 单号签发的公共底座（lenient：仅 create 路径触达，cancel/回执用例不使用不报严格桩告警）
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
        OperatorContextHolder.set("501");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    // ---------------------------------------------------------------- 替身构造

    /**
     * 就诊记录替身（id=77，patientId=9，visitId=O2026092100001）。
     *
     * @param status 就诊状态
     * @return visit 替身，非空
     */
    private Visit visit(VisitStatus status) {
        Visit visit = new Visit();
        visit.setId(77L);
        visit.setVisitId("O2026092100001");
        visit.setPatientId(9L);
        visit.setDeptCode("DEP001");
        visit.setStatus(status);
        return visit;
    }

    /**
     * 申请单替身（id=881，visitId=O2026092100001，EXAM 单）。
     *
     * @param orderNo 申请单号
     * @param type    单据类型
     * @param status  申请单状态
     * @return 申请单替身，非空
     */
    private ClinicOrder order(String orderNo, OrderType type, OrderStatus status) {
        return order(881L, orderNo, type, status);
    }

    /**
     * 申请单替身（指定主键，listByVisit 两单分组断言用）。
     *
     * @param id      申请单主键
     * @param orderNo 申请单号
     * @param type    单据类型
     * @param status  申请单状态
     * @return 申请单替身，非空
     */
    private ClinicOrder order(long id, String orderNo, OrderType type, OrderStatus status) {
        ClinicOrder order = new ClinicOrder();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setVisitId("O2026092100001");
        order.setPatientId(9L);
        order.setOrderType(type);
        order.setOrderDoctorId("501");
        order.setStatus(status);
        return order;
    }

    /** 开单请求替身（LAB 两行：quantity "2" string 逐字锚）。 */
    private OrderCreateRequest createRequest() {
        return new OrderCreateRequest(
                "LAB", List.of(new OrderItemRequest("LAB001", "2", null), new OrderItemRequest("LAB002", "1", "空腹")));
    }

    /** 授权通过桩（PRESCRIPTION 校验通过）。 */
    private void stubPracticePass() {
        when(practiceCheckPort.check(501L, "PRESCRIPTION"))
                .thenReturn(new PracticeCheckResult(true, "执业授权有效：PRESCRIPTION"));
    }

    /** 主单 insert 桩：模拟 MP ASSIGN_ID 回填主键 881。 */
    private void stubOrderInsertAssignsId() {
        doAnswer(inv -> {
                    ClinicOrder o = inv.getArgument(0);
                    o.setId(881L);
                    return 1;
                })
                .when(clinicOrderMapper)
                .insert(any(ClinicOrder.class));
    }

    /** 今日单号（OP+yyyyMMdd+6 位流水段）。 */
    private String orderNoOf(long seq) {
        return "OP" + LocalDate.now().format(SEQ_DATE) + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 冻结用例

    @Test
    @DisplayName("开单发布 order.created：orderId=order_no+lines 两行 itemCode/quantity 逐字（quantity \"2\" string）")
    void createPublishesOrderCreatedWithLines() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        stubPracticePass();
        stubOrderInsertAssignsId();

        ClinicOrderVO vo = service.create("O2026092100001", createRequest());

        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(OutpatientMessagingConstants.EVENT_ORDER_CREATED);
        OrderCreatedPayload payload =
                (OrderCreatedPayload) eventCaptor.getValue().payload();
        assertThat(payload.orderId()).isEqualTo(orderNoOf(1));
        assertThat(payload.patientId()).isEqualTo(9L);
        assertThat(payload.visitId()).isEqualTo("O2026092100001");
        assertThat(payload.lines()).hasSize(2);
        assertThat(payload.lines().get(0).itemCode()).isEqualTo("LAB001");
        assertThat(payload.lines().get(0).quantity()).isEqualTo("2");
        assertThat(payload.lines().get(1).itemCode()).isEqualTo("LAB002");
        // 出参直出（status=CREATED，quantity DECIMAL string 透传）
        assertThat(vo.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(vo.items().get(0).quantity()).isEqualTo("2");
    }

    @Test
    @DisplayName("开单拒绝：执业授权未过（port false）——OP-1017 403 且零写零发布")
    void createRejectsWhenPracticeCheckFails() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        when(practiceCheckPort.check(501L, "PRESCRIPTION"))
                .thenReturn(new PracticeCheckResult(false, "无有效执业授权记录：PRESCRIPTION"));

        assertThatThrownBy(() -> service.create("O2026092100001", createRequest()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.PRACTICE_CHECK_FAILED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        verify(clinicOrderMapper, never()).insert(any(ClinicOrder.class));
        verify(clinicOrderItemMapper, never()).insert(anyCollection());
        verify(events, never()).publishEvent(any(OutpatientDomainEvent.class));
    }

    @Test
    @DisplayName("开单单号连续签发：OP+今日+00001/00002 连续两单")
    void createAssignsSequentialOrderNo() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        stubPracticePass();
        stubOrderInsertAssignsId();
        when(valueOperations.increment(anyString())).thenReturn(1L, 2L);

        service.create("O2026092100001", createRequest());
        service.create("O2026092100001", createRequest());

        verify(clinicOrderMapper, times(2)).insert(orderCaptor.capture());
        assertThat(orderCaptor.getAllValues().get(0).getOrderNo()).isEqualTo(orderNoOf(1));
        assertThat(orderCaptor.getAllValues().get(1).getOrderNo()).isEqualTo(orderNoOf(2));
    }

    @Test
    @DisplayName("作废 PENDING_FEE 单：CAS→CANCELLED+端口逐行作废 sourceRef=orderNo 的 PENDING 行")
    void cancelVoidingPendingFeeViaPort() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.PENDING_FEE);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "PENDING_FEE", "CANCELLED")).thenReturn(1);
        when(billingPort.feesByVisit("O2026092100001"))
                .thenReturn(List.of(
                        new VisitFeeView(601L, "PENDING", orderNoOf(1), "ORDER_CONFIRMED", 5000L, null),
                        new VisitFeeView(602L, "SETTLED", "OTHER-REF", "ORDER_CONFIRMED", 3000L, 55L)));

        ClinicOrderVO vo = service.cancel(orderNoOf(1), "开单作废");

        verify(clinicOrderMapper).casStatus(881L, "PENDING_FEE", "CANCELLED");
        // 仅本单 PENDING 行作废；他单/已缴费行不触碰
        verify(billingPort).cancelPendingFee(601L, "开单作废");
        verify(billingPort, never()).cancelPendingFee(eq(602L), anyString());
        assertThat(vo.status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("作废拒绝：CHARGED 已缴费单——OP-1015 引导 M13 退费链且零端口调用")
    void cancelRejectsChargedOrder() {
        when(clinicOrderMapper.selectOne(any())).thenReturn(order(orderNoOf(1), OrderType.LAB, OrderStatus.CHARGED));

        assertThatThrownBy(() -> service.cancel(orderNoOf(1), "误开")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(billingPort, never()).feesByVisit(anyString());
    }

    @Test
    @DisplayName("作废拒绝：RX_REF 处方引用行——OP-1015 文案含「经 M06 作废链」引导")
    void cancelRejectsRxRefRowWithGuidance() {
        when(clinicOrderMapper.selectOne(any())).thenReturn(order(orderNoOf(1), OrderType.RX_REF, OrderStatus.CREATED));

        assertThatThrownBy(() -> service.cancel(orderNoOf(1), "误开")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED);
            assertThat(e.getMessage()).contains("经 M06 作废链");
        });
        verify(clinicOrderMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("终态就诊开单拒绝（红线 5，经 create 入口断言）：FINISHED visit——OP-1011 且不触达授权校验")
    void finishedVisitRejectsNewOrder() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.FINISHED));

        assertThatThrownBy(() -> service.create("O2026092100001", createRequest()))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.VISIT_STATE_NOT_ALLOWED);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(practiceCheckPort, never()).check(anyLong(), anyString());
        verify(clinicOrderMapper, never()).insert(any(ClinicOrder.class));
    }

    // ---------------------------------------------------------------- 守卫用例（LINE=1.00 分支覆盖）

    @Test
    @DisplayName("开单拒绝：就诊不存在——OP-1001")
    void createRejectsUnknownVisit() {
        when(visitMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create("O2026092199999", createRequest()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.VISIT_NOT_FOUND));
    }

    @Test
    @DisplayName("开单拒绝：单据类型词表外（RX_REF 不经本端点创建）——OP-1019")
    void createRejectsUnknownOrderType() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));

        assertThatThrownBy(() -> service.create(
                        "O2026092100001",
                        new OrderCreateRequest("RX_REF", List.of(new OrderItemRequest("M001", "1", null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID));
        verify(practiceCheckPort, never()).check(anyLong(), anyString());
    }

    @Test
    @DisplayName("开单拒绝：quantity 非数字串（W-22⑦ 禁裸 parse 前置防线）——OP-1019")
    void createRejectsInvalidQuantityFormat() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));

        assertThatThrownBy(() -> service.create(
                        "O2026092100001",
                        new OrderCreateRequest("LAB", List.of(new OrderItemRequest("LAB001", "2件", null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID));
        verify(clinicOrderMapper, never()).insert(any(ClinicOrder.class));
    }

    @Test
    @DisplayName("开单拒绝：操作者标识非数字（无法定位执业授权主体）——OP-1019 且不触达端口")
    void createRejectsUnparsableOperator() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        OperatorContextHolder.set("doctor-x");

        assertThatThrownBy(() -> service.create("O2026092100001", createRequest()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.PARAM_FORMAT_INVALID));
        verify(practiceCheckPort, never()).check(anyLong(), anyString());
    }

    @Test
    @DisplayName("开单拒绝：Redis 流水返回空——单号签发 fail-fast（违例值禁落库）")
    void createRejectsWhenRedisSeqMissing() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        stubPracticePass();
        when(valueOperations.increment(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.create("O2026092100001", createRequest()))
                .isInstanceOf(IllegalStateException.class);
        verify(clinicOrderMapper, never()).insert(any(ClinicOrder.class));
    }

    @Test
    @DisplayName("作废拒绝：申请单不存在——OP-1014")
    void cancelRejectsUnknownOrder() {
        when(clinicOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.cancel("OP20260921999999", "误开"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    @DisplayName("作废拒绝：IN_EXECUTION 声明态单据——OP-1015（状态机非可作废面）")
    void cancelRejectsWhenOrderInExecutionState() {
        when(clinicOrderMapper.selectOne(any()))
                .thenReturn(order(orderNoOf(1), OrderType.LAB, OrderStatus.IN_EXECUTION));

        assertThatThrownBy(() -> service.cancel(orderNoOf(1), "误开")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED);
            assertThat(e.getMessage()).contains("当前态 IN_EXECUTION");
        });
        verifyNoInteractions(billingPort);
    }

    @Test
    @DisplayName("作废拒绝：CAS 并发落败——OP-1015 且零端口调用")
    void cancelRejectsWhenCasConcurrentLose() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "CANCELLED")).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(orderNoOf(1), "误开"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED));
        verifyNoInteractions(billingPort);
    }

    @Test
    @DisplayName("作废端口底层异常转译：RuntimeException→OP-1015 可读业务错误（禁裸抛，W-20 关联面）")
    void cancelTranslatesPortFailureToBizException() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "CANCELLED")).thenReturn(1);
        when(billingPort.feesByVisit("O2026092100001"))
                .thenReturn(List.of(new VisitFeeView(601L, "PENDING", orderNoOf(1), "ORDER_CONFIRMED", 5000L, null)));
        doThrow(new RuntimeException("connection refused")).when(billingPort).cancelPendingFee(601L, "误开");

        assertThatThrownBy(() -> service.cancel(orderNoOf(1), "误开")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(OutpatientErrorCode.ORDER_STATE_NOT_ALLOWED);
            assertThat(e.getMessage()).contains("M13 端口异常");
        });
    }

    @Test
    @DisplayName("作废端口业务拒绝透传：billing BizException 原样上抛（BILL-xxxx 可读可定位）")
    void cancelPropagatesBillingBizRejection() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "CANCELLED")).thenReturn(1);
        when(billingPort.feesByVisit("O2026092100001"))
                .thenReturn(List.of(new VisitFeeView(601L, "PENDING", orderNoOf(1), "ORDER_CONFIRMED", 5000L, null)));
        BizException billingReject =
                new BizException(BillingErrorCode.FEE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "费用行非未结算态");
        doThrow(billingReject).when(billingPort).cancelPendingFee(601L, "误开");

        assertThatThrownBy(() -> service.cancel(orderNoOf(1), "误开")).isSameAs(billingReject);
    }

    @Test
    @DisplayName("缴费回执推进：单据 CREATED→PENDING_FEE+visit IN_CONSULT→PENDING_FEE+每迁必记")
    void markPendingFeeMovesOrderAndVisitToPendingFee() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "PENDING_FEE")).thenReturn(1);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        when(visitMapper.casStatus(77L, "IN_CONSULT", "PENDING_FEE")).thenReturn(1);

        service.markPendingFee(orderNoOf(1));

        // visit 状态机合法迁移对+每迁必记（红线 5）
        verify(visitMapper).casStatus(77L, "IN_CONSULT", "PENDING_FEE");
        verify(visitStatusLogMapper).insert(statusLogCaptor.capture());
        assertThat(statusLogCaptor.getValue().getFromStatus()).isEqualTo(VisitStatus.IN_CONSULT);
        assertThat(statusLogCaptor.getValue().getToStatus()).isEqualTo(VisitStatus.PENDING_FEE);
    }

    @Test
    @DisplayName("缴费回执幂等跳过：单据已 PENDING_FEE（重复投递重读定性）——零 visit 触达")
    void markPendingFeeSkipsWhenOrderAlreadyPendingFee() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.PENDING_FEE);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "PENDING_FEE")).thenReturn(0);
        when(clinicOrderMapper.selectById(881L)).thenReturn(order);

        service.markPendingFee(orderNoOf(1));

        verify(visitMapper, never()).selectOne(any());
        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("缴费回执跳过：单据当前态 CANCELLED（终态竞态 warn 留痕，对账兜底）——零 visit 触达")
    void markPendingFeeSkipsWhenOrderCancelled() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "PENDING_FEE")).thenReturn(0);
        when(clinicOrderMapper.selectById(881L)).thenReturn(order(orderNoOf(1), OrderType.LAB, OrderStatus.CANCELLED));

        service.markPendingFee(orderNoOf(1));

        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("缴费回执推进失败：申请单缺失（数据异常）——IllegalStateException 死信留痕")
    void markPendingFeeRejectsMissingOrder() {
        when(clinicOrderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.markPendingFee("OP20260921999999")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("缴费回执消费线程无请求上下文：operator 落 system 哨兵（R1 修——ThreadLocal 不跨线程，NOT NULL 列防回归）")
    void markPendingFeeStampsSystemOperatorWithoutRequestContext() {
        // MQ 消费线程无 ThreadLocal 操作者上下文（setUp 预置清空模拟真实消费环境）
        OperatorContextHolder.clear();
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "PENDING_FEE")).thenReturn(1);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        when(visitMapper.casStatus(77L, "IN_CONSULT", "PENDING_FEE")).thenReturn(1);

        service.markPendingFee(orderNoOf(1));

        verify(visitStatusLogMapper).insert(statusLogCaptor.capture());
        // operator 必为 system 哨兵（null 落 NOT NULL 列即违反约束致回执反复进死信——Critical-1 防回归锚）
        assertThat(statusLogCaptor.getValue().getOperator()).isEqualTo("system");
    }

    @Test
    @DisplayName("缴费回执 visit 跳过：visit 已非 IN_CONSULT（多单并推/诊毕竞态正常态）——零 CAS")
    void markPendingFeeSkipsWhenVisitNotInConsult() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "PENDING_FEE")).thenReturn(1);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.PENDING_FEE));

        service.markPendingFee(orderNoOf(1));

        verify(visitMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
    }

    @Test
    @DisplayName("缴费回执 visit 并发落败：CAS 0 行 warn 跳过且零留痕（回执单据侧已推进）")
    void markPendingFeeWarnsWhenVisitCasConcurrentLose() {
        ClinicOrder order = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        when(clinicOrderMapper.selectOne(any())).thenReturn(order);
        when(clinicOrderMapper.casStatus(881L, "CREATED", "PENDING_FEE")).thenReturn(1);
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        when(visitMapper.casStatus(77L, "IN_CONSULT", "PENDING_FEE")).thenReturn(0);

        service.markPendingFee(orderNoOf(1));

        verify(visitStatusLogMapper, never()).insert(any(VisitStatusLog.class));
    }

    @Test
    @DisplayName("按就诊号查询申请单：无单据返回空列表（空分支）")
    void listByVisitReturnsEmptyListWhenNoOrders() {
        when(clinicOrderMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listByVisit("O2026092100001")).isEmpty();
        verify(clinicOrderItemMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("开单拒绝：当日流水超 6 位上限——单号签发 fail-fast（违例值禁落库）")
    void createRejectsWhenRedisSeqOverCap() {
        when(visitMapper.selectOne(any())).thenReturn(visit(VisitStatus.IN_CONSULT));
        stubPracticePass();
        when(valueOperations.increment(anyString())).thenReturn(1000000L);

        assertThatThrownBy(() -> service.create("O2026092100001", createRequest()))
                .isInstanceOf(IllegalStateException.class);
        verify(clinicOrderMapper, never()).insert(any(ClinicOrder.class));
    }

    @Test
    @DisplayName("按就诊号查询申请单：两单各配明细行（批量装配，禁 N+1）")
    void listByVisitReturnsOrdersWithItems() {
        ClinicOrder first = order(orderNoOf(1), OrderType.LAB, OrderStatus.CREATED);
        ClinicOrder second = order(882L, orderNoOf(2), OrderType.EXAM, OrderStatus.CHARGED);
        when(clinicOrderMapper.selectList(any())).thenReturn(List.of(second, first));
        ClinicOrderItem labRow = new ClinicOrderItem();
        labRow.setOrderId(881L);
        labRow.setItemCode("LAB001");
        labRow.setQuantity("2");
        ClinicOrderItem examRow = new ClinicOrderItem();
        examRow.setOrderId(882L);
        examRow.setItemCode("EXAM001");
        examRow.setQuantity("1");
        when(clinicOrderItemMapper.selectList(any())).thenReturn(List.of(labRow, examRow));

        List<ClinicOrderVO> result = service.listByVisit("O2026092100001");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).orderNo()).isEqualTo(orderNoOf(2));
        assertThat(result.get(0).items())
                .extracting(ClinicOrderVO.Item::itemCode)
                .containsExactly("EXAM001");
        assertThat(result.get(1).orderNo()).isEqualTo(orderNoOf(1));
        assertThat(result.get(1).items())
                .extracting(ClinicOrderVO.Item::itemCode)
                .containsExactly("LAB001");
    }
}
