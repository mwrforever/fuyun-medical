package com.fuyun.inpatient.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.fuyun.inpatient.api.payload.OrderCreatedItem;
import com.fuyun.inpatient.api.payload.OrderCreatedPayload;
import com.fuyun.inpatient.api.payload.OrderStoppedPayload;
import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import com.fuyun.inpatient.dto.OrderCreateRequest;
import com.fuyun.inpatient.dto.OrderItemRequest;
import com.fuyun.inpatient.entity.InpatientVisit;
import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import com.fuyun.inpatient.entity.OrderFrequency;
import com.fuyun.inpatient.enums.OrderClass;
import com.fuyun.inpatient.enums.OrderStatus;
import com.fuyun.inpatient.enums.VisitStatus;
import com.fuyun.inpatient.internal.InpatientDomainEvent;
import com.fuyun.inpatient.mapper.InpatientVisitMapper;
import com.fuyun.inpatient.mapper.MedicalOrderItemMapper;
import com.fuyun.inpatient.mapper.MedicalOrderMapper;
import com.fuyun.inpatient.mapper.OrderFrequencyMapper;
import com.fuyun.inpatient.service.OrderStateMachineService;
import com.fuyun.inpatient.vo.MedicalOrderVO;
import com.fuyun.inpatient.vo.OrderDetailVO;
import com.fuyun.patient.api.AllergyChecker;
import com.fuyun.patient.api.AllergyItem;
import com.fuyun.system.api.PracticeCheckPort;
import com.fuyun.system.api.PracticeCheckResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

/**
 * 医嘱开立域服务单测（Task 5 冻结八用例 + 覆盖率补面）：四层校验顺序与错误面（授权/过敏/
 * 明细频次/嘱托）、开立落库与 order.created 子键路由载荷、成组三要素、转科批量停嘱链
 * （状态机迁移+停嘱值面+stopped 事件）、查询面与守卫补充面（操作者非数字/非在院/词表外/
 * 号冲突）。MP 3.5.17 单测范式：lambdaQuery 触达实体 @BeforeAll 手工注册表信息。
 * OrderStateMachineService 按冻结接口 mock（状态面语义由 OrderStateMachineServiceImplTest
 * 独立承载）。
 */
@ExtendWith(MockitoExtension.class)
class MedicalOrderServiceImplTest {

    /** 编排主体 I 型 14 位就诊号 */
    private static final String VISIT_ID = "I2026092500001";

    /** 就诊行主键（medical_order.visit_id 消费面——非 I 型号） */
    private static final long VISIT_PK = 7001L;

    /** 患者主索引 */
    private static final long PATIENT_ID = 1001L;

    /** 操作者员工 ID（数字形态——执业授权与审计口径） */
    private static final long OPERATOR = 1001L;

    /** 医嘱号（InpatientSeqGate mock 签发值） */
    private static final String ORDER_NO = "MO2026092500001";

    @Mock
    private MedicalOrderMapper orderMapper;

    @Mock
    private MedicalOrderItemMapper itemMapper;

    @Mock
    private OrderFrequencyMapper frequencyMapper;

    @Mock
    private InpatientVisitMapper visitMapper;

    @Mock
    private InpatientSeqGate seqGate;

    @Mock
    private PracticeCheckPort practiceCheckPort;

    @Mock
    private AllergyChecker allergyChecker;

    @Mock
    private OrderStateMachineService stateMachine;

    @Mock
    private ApplicationEventPublisher events;

    @Captor
    private ArgumentCaptor<MedicalOrder> orderCaptor;

    @Captor
    private ArgumentCaptor<MedicalOrderItem> itemCaptor;

    @Captor
    private ArgumentCaptor<InpatientDomainEvent> eventCaptor;

    private MedicalOrderServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体须手工注册表信息（四实体查询面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrder.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MedicalOrderItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderFrequency.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), InpatientVisit.class);
    }

    @BeforeEach
    void setUp() {
        service = new MedicalOrderServiceImpl(
                orderMapper,
                itemMapper,
                frequencyMapper,
                visitMapper,
                seqGate,
                practiceCheckPort,
                allergyChecker,
                stateMachine,
                events);
        OperatorContextHolder.set(String.valueOf(OPERATOR));
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("用例①开立成功（长期用药医嘱）：四层全过→CREATED 落库→order.created 载荷含 items 明细→drug 子键路由")
    void createRunsFourLayersAndPublishesDrugRoutedEvent() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(frequencyMapper.selectOne(any())).thenReturn(frequencyRow("qd"));
        when(seqGate.nextNo("MO")).thenReturn(ORDER_NO);

        MedicalOrderVO result = service.create(VISIT_ID, longDrugOrder());

        // 出参：CREATED 初始态、成组组号缺省回填本医嘱号、医生=操作者上下文
        assertThat(result.orderNo()).isEqualTo(ORDER_NO);
        assertThat(result.status()).isEqualTo(OrderStatus.CREATED.getCode());
        assertThat(result.orderType()).isEqualTo("DRUG");
        assertThat(result.orderClass()).isEqualTo("LONG");
        assertThat(result.groupNo()).isEqualTo(ORDER_NO);
        assertThat(result.freqCode()).isEqualTo("qd");
        assertThat(result.visitId()).isEqualTo(VISIT_ID);
        assertThat(result.patientId()).isEqualTo(PATIENT_ID);
        assertThat(result.doctorId()).isEqualTo(String.valueOf(OPERATOR));

        // 主表落库：visit_id 存就诊主键（非 I 型号）、standby 缺省 false
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getVisitId()).isEqualTo(VISIT_PK);
        assertThat(orderCaptor.getValue().getStandbyFlag()).isFalse();
        assertThat(orderCaptor.getValue().getOrderedAt()).isNotNull();

        // 子表落库：item_seq 1 起递增、名称快照、计费回执两列默认 false
        verify(itemMapper).insert(itemCaptor.capture());
        MedicalOrderItem savedItem = itemCaptor.getValue();
        assertThat(savedItem.getItemSeq()).isEqualTo(1);
        assertThat(savedItem.getNameSnapshot()).isEqualTo("头孢呋辛钠注射液");
        assertThat(savedItem.getFeePriced()).isFalse();
        assertThat(savedItem.getFeeStopped()).isFalse();

        // 事件：routing 子键 drug（DomainEventSender eventType 双作信封类型与路由键）；
        // 载荷头小写子键口径 + items 明细（剂量拼串/数量 DECIMAL string/行类型小写）
        verify(events).publishEvent(eventCaptor.capture());
        InpatientDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType())
                .isEqualTo(InpatientMessagingConstants.withTypeKey(
                        InpatientMessagingConstants.EVENT_ORDER_CREATED, "drug"));
        OrderCreatedPayload payload = (OrderCreatedPayload) event.payload();
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.visitId()).isEqualTo(VISIT_ID);
        assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
        assertThat(payload.orderType()).isEqualTo("drug");
        assertThat(payload.orderClass()).isEqualTo("LONG");
        assertThat(payload.standbyFlag()).isFalse();
        assertThat(payload.groupNo()).isEqualTo(ORDER_NO);
        assertThat(payload.freqCode()).isEqualTo("qd");
        assertThat(payload.items()).hasSize(1);
        OrderCreatedItem line = payload.items().get(0);
        assertThat(line.itemSeq()).isEqualTo(1);
        assertThat(line.itemCode()).isEqualTo("D0001");
        assertThat(line.itemName()).isEqualTo("头孢呋辛钠注射液");
        assertThat(line.dosage()).isEqualTo("0.5g");
        assertThat(line.unit()).isEqualTo("g");
        assertThat(line.route()).isEqualTo("IV");
        assertThat(line.quantity()).isEqualTo("2");
        assertThat(line.itemType()).isEqualTo("drug");
    }

    @Test
    @DisplayName("用例②无处方权被拒：PracticeCheckPort passed=false→IP-1012（403），四层后续与落库/事件零触达")
    void createRejectsPracticeForbidden() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION"))
                .thenReturn(new PracticeCheckResult(false, "无有效执业授权记录 PRESCRIPTION"));

        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.PRACTICE_FORBIDDEN);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    // 文案工号脱敏（等保三级口径，禁明文工号出 ProblemDetail）
                    assertThat(((BizException) e).getMessage()).doesNotContain(String.valueOf(OPERATOR));
                });
        // L1 拒绝即整单不入库：过敏/频次/落库/事件零触达
        verifyNoInteractions(allergyChecker, frequencyMapper, seqGate, orderMapper, itemMapper, events);
    }

    @Test
    @DisplayName("用例③过敏强阳性拦截：药品行 item_code 命中有效过敏项 code→IP-1013（409），落库/事件零触达")
    void createRejectsAllergyConflict() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID))
                .thenReturn(List.of(new AllergyItem(9L, "D0001", "头孢呋辛", "SEVERE")));

        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ALLERGY_CONFLICT);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BizException) e).getMessage()).contains("头孢呋辛钠注射液");
                });
        // L2 拒绝即整单不入库：频次/落库/事件零触达
        verifyNoInteractions(frequencyMapper, seqGate, orderMapper, itemMapper, events);
    }

    @Test
    @DisplayName("用例④剂量缺失拒绝：药品行 dosage 空→IP-1011（400），频次校验前置短路零触达")
    void createRejectsMissingDosage() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        OrderItemRequest drugNoDosage = new OrderItemRequest(
                "DRUG", "D0001", "头孢呋辛钠注射液", null, "g", "IV", null, new BigDecimal("2"), null, null, null, null);

        assertThatThrownBy(() -> service.create(
                        VISIT_ID, new OrderCreateRequest("DRUG", "LONG", null, null, "qd", List.of(drugNoDosage))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_ITEM_INVALID);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        // 药品行必填短路在频次字典查询之前（同层内先明细后频次）
        verifyNoInteractions(frequencyMapper, orderMapper, itemMapper, events);
    }

    @Test
    @DisplayName("用例⑤频次不存在：order_frequency 无命中→IP-1021（404）")
    void createRejectsUnknownFrequency() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(frequencyMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.FREQUENCY_NOT_FOUND);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verifyNoInteractions(seqGate, orderMapper, itemMapper, events);
    }

    @Test
    @DisplayName("用例⑥嘱托仅长期可用：STAT+standby→IP-1022（400），落库/事件零触达")
    void createRejectsStandbyOnStatOrder() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(
                        VISIT_ID, new OrderCreateRequest("DRUG", "STAT", true, null, null, List.of(drugItem(true)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID);
                    assertThat(((BizException) e).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verifyNoInteractions(seqGate, orderMapper, itemMapper, events);
    }

    @Test
    @DisplayName("用例⑦成组医嘱三要素：groupNo 沿传入组号、item_seq 组内 1/2 递增、continue_flag 逐行落库")
    void createPersistsGroupElements() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(frequencyMapper.selectOne(any())).thenReturn(frequencyRow("bid"));
        when(seqGate.nextNo("MO")).thenReturn(ORDER_NO);

        // 成组两行：药品行（延续）+检验行（非延续、无剂量——载荷剂量空分支同覆盖）
        service.create(
                VISIT_ID,
                new OrderCreateRequest(
                        "DRUG",
                        "LONG",
                        null,
                        "GRP20260925001",
                        "bid",
                        List.of(
                                drugItem(true),
                                new OrderItemRequest(
                                        "LAB",
                                        "L0001",
                                        "血常规",
                                        null,
                                        null,
                                        null,
                                        null,
                                        new BigDecimal("1"),
                                        null,
                                        null,
                                        null,
                                        false))));

        // 主表组号沿传入值（缺省回填仅在缺席时发生）
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getGroupNo()).isEqualTo("GRP20260925001");
        // 子表两行：序号组内递增、延续标志逐行、计费回执默认 false
        verify(itemMapper, times(2)).insert(itemCaptor.capture());
        List<MedicalOrderItem> saved = itemCaptor.getAllValues();
        assertThat(saved).extracting(MedicalOrderItem::getItemSeq).containsExactly(1, 2);
        assertThat(saved).extracting(MedicalOrderItem::getContinueFlag).containsExactly(true, false);
        // 载荷剂量拼串空分支：检验行 dosage 原样（null）透传
        verify(events).publishEvent(eventCaptor.capture());
        OrderCreatedPayload payload =
                (OrderCreatedPayload) eventCaptor.getValue().payload();
        assertThat(payload.groupNo()).isEqualTo("GRP20260925001");
        assertThat(payload.items()).hasSize(2);
        assertThat(payload.items().get(1).dosage()).isNull();
        assertThat(payload.items().get(1).itemType()).isEqualTo("lab");
    }

    @Test
    @DisplayName("用例⑧转科批量停嘱：长期可停医嘱逐条 STOPPED 迁移+停嘱值面+stopped 事件（reason=转科）；空集直过")
    void stopAllForTransferStopsOngoingLongOrders() {
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        MedicalOrder first = orderRow("MO1", OrderStatus.AUDITED);
        MedicalOrder second = orderRow("MO2", OrderStatus.EXECUTING);
        when(orderMapper.selectList(any())).thenReturn(List.of(first, second));
        when(orderMapper.updateStopValues(eq("MO1"), any(), eq("转科"), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(orderMapper.updateStopValues(eq("MO2"), any(), eq("转科"), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);

        service.stopAllForTransfer(VISIT_PK, "转科");

        // 逐条经状态机（STOPPED 迁移唯一裁决面）+ 停嘱值面（服务器时间+原因）
        InOrder order = Mockito.inOrder(stateMachine, orderMapper);
        order.verify(stateMachine).transition(first, OrderStatus.STOPPED, "转科", OPERATOR);
        order.verify(orderMapper)
                .updateStopValues(eq("MO1"), any(OffsetDateTime.class), eq("转科"), eq(String.valueOf(OPERATOR)));
        order.verify(stateMachine).transition(second, OrderStatus.STOPPED, "转科", OPERATOR);
        order.verify(orderMapper)
                .updateStopValues(eq("MO2"), any(OffsetDateTime.class), eq("转科"), eq(String.valueOf(OPERATOR)));

        // 两条 stopped 事件（V800 id 44 载荷：m04OrderNo/visitId 号/patientId/stoppedAt/stopOperator/stopReason）
        verify(events, times(2)).publishEvent(eventCaptor.capture());
        List<OrderStoppedPayload> payloads = eventCaptor.getAllValues().stream()
                .map(InpatientDomainEvent::payload)
                .map(OrderStoppedPayload.class::cast)
                .toList();
        assertThat(payloads).extracting(OrderStoppedPayload::m04OrderNo).containsExactly("MO1", "MO2");
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload.visitId()).isEqualTo(VISIT_ID);
            assertThat(payload.patientId()).isEqualTo(PATIENT_ID);
            assertThat(payload.stopOperator()).isEqualTo(String.valueOf(OPERATOR));
            assertThat(payload.stopReason()).isEqualTo("转科");
            assertThat(payload.stoppedAt()).isNotNull();
        });

        // 空集直过：无可停长期医嘱零迁移零事件（转科编排不受空集阻断）
        Mockito.reset(stateMachine, events);
        when(orderMapper.selectList(any())).thenReturn(List.of());
        service.stopAllForTransfer(VISIT_PK, "转科");
        verifyNoInteractions(stateMachine, events);
    }

    @Test
    @DisplayName("查询面与单条停嘱：list 分类两分支+detail 明细聚合+stop 共用停嘱链")
    void listDetailAndStopCoverReadAndSingleStopFace() {
        // list：就诊过滤+分类条件缺席两分支（开立时间倒序由 orderByDesc 承载）
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        Page<MedicalOrder> page = new Page<>(1, 20);
        page.setRecords(List.of(orderRow(ORDER_NO, OrderStatus.CREATED)));
        page.setTotal(1);
        when(orderMapper.selectPage(any(), any())).thenReturn(page);
        PageResult<MedicalOrderVO> allClasses = service.list(VISIT_ID, null, 0, 20);
        PageResult<MedicalOrderVO> longOnly = service.list(VISIT_ID, OrderClass.LONG, 0, 20);
        assertThat(allClasses.total()).isEqualTo(1);
        assertThat(allClasses.content().get(0).visitId()).isEqualTo(VISIT_ID);
        assertThat(longOnly.content()).hasSize(1);

        // detail：头+明细行全集（item_seq 升序由 orderByAsc 承载）
        MedicalOrder row = orderRow(ORDER_NO, OrderStatus.CREATED);
        when(orderMapper.selectOne(any())).thenReturn(row);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        MedicalOrderItem line = new MedicalOrderItem();
        line.setItemSeq(1);
        line.setItemType("DRUG");
        line.setItemCode("D0001");
        line.setNameSnapshot("头孢呋辛钠注射液");
        line.setQuantity(new BigDecimal("2"));
        line.setContinueFlag(false);
        line.setSkinTestFlag(false);
        line.setOralFlag(false);
        line.setFeePriced(false);
        line.setFeeStopped(false);
        when(itemMapper.selectList(any())).thenReturn(List.of(line));
        OrderDetailVO detail = service.detail(ORDER_NO);
        assertThat(detail.order().orderNo()).isEqualTo(ORDER_NO);
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).nameSnapshot()).isEqualTo("头孢呋辛钠注射液");

        // stop（Task 6 端点消费面）：与 stopAllForTransfer 共用 stopInternal
        when(orderMapper.updateStopValues(eq(ORDER_NO), any(), eq("病情好转"), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        service.stop(ORDER_NO, "病情好转");
        verify(stateMachine).transition(row, OrderStatus.STOPPED, "病情好转", OPERATOR);
        verify(events).publishEvent(eventCaptor.capture());
        OrderStoppedPayload payload =
                (OrderStoppedPayload) eventCaptor.getValue().payload();
        assertThat(payload.m04OrderNo()).isEqualTo(ORDER_NO);
        assertThat(payload.stopReason()).isEqualTo("病情好转");
    }

    @Test
    @DisplayName("守卫补充面：操作者非数字 IP-1022/非在院 IP-1008/就诊不存在 IP-1007/orderType 词表外 IP-1022/医嘱号冲突 IP-1023")
    void guardFacesRejectMalformedOperatorAndConflicts() {
        // 操作者标识非数字（无法定位执业授权主体）：IP-1022，授权端口零触达
        OperatorContextHolder.set("doc-01");
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));
        verifyNoInteractions(practiceCheckPort);
        OperatorContextHolder.set(String.valueOf(OPERATOR));

        // 短标识脱敏分支（len<=2 全掩码）：文案不含原值
        OperatorContextHolder.set("ab");
        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage()).doesNotContain("ab"));
        OperatorContextHolder.set(String.valueOf(OPERATOR));

        // 非在院态（已出院）：IP-1008
        InpatientVisit discharged = visitRow();
        discharged.setStatus(VisitStatus.DISCHARGED.getCode());
        when(visitMapper.selectOne(any())).thenReturn(discharged);
        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.VISIT_STATE_NOT_ALLOWED));

        // 就诊不存在：IP-1007
        when(visitMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // 医嘱类型词表外（服务面——Web 层 @Pattern 兜底）：IP-1022
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        assertThatThrownBy(() -> service.create(
                        VISIT_ID, new OrderCreateRequest("PHYSIO", "LONG", null, null, "qd", List.of(drugItem(true)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 医嘱号唯一冲突（uk 兜底发号幂等）：IP-1023
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        when(frequencyMapper.selectOne(any())).thenReturn(frequencyRow("qd"));
        when(seqGate.nextNo("MO")).thenReturn(ORDER_NO);
        doThrow(new DuplicateKeyException("uk_medical_order_no"))
                .when(orderMapper)
                .insert(any(MedicalOrder.class));
        assertThatThrownBy(() -> service.create(VISIT_ID, longDrugOrder()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(itemMapper, events);
    }

    @Test
    @DisplayName("开立边界补面：orderClass/itemType 词表外 IP-1022、长期缺频次 IP-1011、过敏空 code 项不匹配放行、非药行剂量拼串无单位分支")
    void createCoversWordbookFrequencyAndAllergyCodeFilterBranches() {
        when(visitMapper.selectOne(any())).thenReturn(visitRow());
        when(practiceCheckPort.check(OPERATOR, "PRESCRIPTION")).thenReturn(new PracticeCheckResult(true, null));

        // 医嘱分类词表外（服务面——Web 层 @Pattern 兜底）：IP-1022
        assertThatThrownBy(() -> service.create(
                        VISIT_ID, new OrderCreateRequest("DRUG", "urgent", null, null, "qd", List.of(drugItem(false)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 行项目类型词表外：IP-1022（L3 明细循环内拦截）
        assertThatThrownBy(() -> service.create(
                        VISIT_ID,
                        new OrderCreateRequest(
                                "DRUG",
                                "LONG",
                                null,
                                null,
                                "qd",
                                List.of(new OrderItemRequest(
                                        "FOOD",
                                        "F001",
                                        "流食",
                                        null,
                                        null,
                                        null,
                                        null,
                                        new BigDecimal("1"),
                                        null,
                                        null,
                                        null,
                                        false)))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(InpatientErrorCode.PARAM_FORMAT_INVALID));

        // 长期医嘱缺频次：IP-1011（频次必填在字典命中查询之前）
        when(allergyChecker.listActiveAllergies(PATIENT_ID)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(
                        VISIT_ID, new OrderCreateRequest("DRUG", "LONG", null, null, null, List.of(drugItem(false)))))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_ITEM_INVALID));
        verifyNoInteractions(frequencyMapper, orderMapper, events);

        // 过敏清单含空 code 项（手工录入无字典对照）不参与 code 匹配——药品行不命中放行，
        // 过敏循环正常走完；检验行携剂量无单位（非药行）触达载荷拼串无单位分支
        when(allergyChecker.listActiveAllergies(PATIENT_ID))
                .thenReturn(List.of(new AllergyItem(9L, null, "芒果", "MILD")));
        when(frequencyMapper.selectOne(any())).thenReturn(frequencyRow("qd"));
        when(seqGate.nextNo("MO")).thenReturn(ORDER_NO);
        MedicalOrderVO result = service.create(
                VISIT_ID,
                new OrderCreateRequest(
                        "DRUG",
                        "LONG",
                        null,
                        null,
                        "qd",
                        List.of(
                                drugItem(false),
                                new OrderItemRequest(
                                        "LAB",
                                        "L0001",
                                        "血常规",
                                        "1",
                                        null,
                                        null,
                                        null,
                                        new BigDecimal("1"),
                                        null,
                                        null,
                                        null,
                                        false))));
        assertThat(result.status()).isEqualTo(OrderStatus.CREATED.getCode());
        verify(events).publishEvent(eventCaptor.capture());
        OrderCreatedPayload payload =
                (OrderCreatedPayload) eventCaptor.getValue().payload();
        // 非药行剂量有值无单位：拼串原样返回（不加 null 单位）
        assertThat(payload.items().get(1).dosage()).isEqualTo("1");
        assertThat(payload.items().get(1).unit()).isNull();
    }

    @Test
    @DisplayName("转科停嘱防御面：就诊主键无行 IP-1007、停嘱值面零行 IP-1023、事件号映射行缺失 IP-1007")
    void stopAllForTransferCoversDefensiveBranches() {
        // 就诊主键无行（编排调用前已校验，此处防御分支）：IP-1007
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.stopAllForTransfer(VISIT_PK, "转科"))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));

        // 停嘱值面落写零行（并发逻辑删窗口）：IP-1023，事件零发布
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow());
        MedicalOrder ongoing = orderRow("MO1", OrderStatus.AUDITED);
        when(orderMapper.selectList(any())).thenReturn(List.of(ongoing));
        when(orderMapper.updateStopValues(eq("MO1"), any(), eq("转科"), eq(String.valueOf(OPERATOR))))
                .thenReturn(0);
        assertThatThrownBy(() -> service.stopAllForTransfer(VISIT_PK, "转科"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.CONFLICT));
        verifyNoInteractions(events);

        // 事件号映射行缺失（数据不一致）：状态已迁移但事务整体回滚（@Transactional 语义），IP-1007
        Mockito.reset(orderMapper);
        when(orderMapper.selectList(any())).thenReturn(List.of(orderRow("MO2", OrderStatus.EXECUTING)));
        when(orderMapper.updateStopValues(eq("MO2"), any(), eq("转科"), eq(String.valueOf(OPERATOR))))
                .thenReturn(1);
        when(visitMapper.selectById(VISIT_PK)).thenReturn(visitRow(), null);
        assertThatThrownBy(() -> service.stopAllForTransfer(VISIT_PK, "转科"))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("单条停嘱与详情定位防御：医嘱不存在 IP-1009、详情关联就诊缺失 IP-1007")
    void stopAndDetailCoverMissingRowBranches() {
        // 单条停嘱医嘱不存在：IP-1009
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.stop("MO404", "病情好转"))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.ORDER_NOT_FOUND));

        // 详情关联就诊缺失（数据不一致）：IP-1007
        when(orderMapper.selectOne(any())).thenReturn(orderRow(ORDER_NO, OrderStatus.CREATED));
        when(visitMapper.selectById(VISIT_PK)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(ORDER_NO))
                .isInstanceOf(BizException.class)
                .satisfies(e ->
                        assertThat(((BizException) e).getErrorCode()).isEqualTo(InpatientErrorCode.VISIT_NOT_FOUND));
    }

    /** 构造在院就诊行（ADMITTED——开立守卫链⓪载体）。 */
    private InpatientVisit visitRow() {
        InpatientVisit row = new InpatientVisit();
        row.setId(VISIT_PK);
        row.setVisitId(VISIT_ID);
        row.setPatientId(PATIENT_ID);
        row.setCurrentWardId("W01");
        row.setStatus(VisitStatus.ADMITTED.getCode());
        return row;
    }

    /** 构造长期用药开立入参（单药品行：剂量/单位/途径齐全）。 */
    private OrderCreateRequest longDrugOrder() {
        return new OrderCreateRequest("DRUG", "LONG", null, null, "qd", List.of(drugItem(false)));
    }

    /** 构造药品明细行（延续标志可变——成组三要素载体）。 */
    private OrderItemRequest drugItem(boolean continueFlag) {
        return new OrderItemRequest(
                "DRUG",
                "D0001",
                "头孢呋辛钠注射液",
                "0.5",
                "g",
                "IV",
                null,
                new BigDecimal("2"),
                null,
                null,
                null,
                continueFlag);
    }

    /** 构造频次字典行（词表内命中载体）。 */
    private OrderFrequency frequencyRow(String freqCode) {
        OrderFrequency row = new OrderFrequency();
        row.setFreqCode(freqCode);
        row.setTimesPerDay(1);
        row.setTimePoints("08:00");
        row.setPrnFlag(false);
        row.setDictCode(freqCode);
        return row;
    }

    /** 构造指定状态医嘱行（停嘱/查询载体）。 */
    private MedicalOrder orderRow(String orderNo, OrderStatus status) {
        MedicalOrder row = new MedicalOrder();
        row.setId(9001L);
        row.setOrderNo(orderNo);
        row.setVisitId(VISIT_PK);
        row.setPatientId(PATIENT_ID);
        row.setOrderType("DRUG");
        row.setOrderClass("LONG");
        row.setGroupNo(orderNo);
        row.setDoctorId(String.valueOf(OPERATOR));
        row.setOrderedAt(OffsetDateTime.now());
        row.setStatus(status.getCode());
        return row;
    }
}
