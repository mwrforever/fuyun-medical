package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.DispenseCompletedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.PickLine;
import com.fuyun.pharmacy.dto.PickRequest;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.DrugBatch;
import com.fuyun.pharmacy.entity.StockLedger;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.mapper.StockLedgerMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 调剂三段单测：pick 批次锁定/追溯码采集、verify 双签守卫、issue 扣减+出库流水+completed 事件、
 * 并发超发拒（PH-1010）与状态机违例拒（PH-1009/1011）。
 */
@ExtendWith(MockitoExtension.class)
class DispenseThreeStepTest {

    @Mock
    private DispenseMapper dispenseMapper;

    @Mock
    private DispenseItemMapper dispenseItemMapper;

    @Mock
    private DrugBatchMapper drugBatchMapper;

    @Mock
    private StockLedgerMapper stockLedgerMapper;

    @Mock
    private PrescriptionMapper prescriptionMapper;

    @Mock
    private PrescriptionItemMapper prescriptionItemMapper;

    @Mock
    private IBatchSelectService batchSelectService;

    @Mock
    private ApplicationEventPublisher events;

    /** Task 10 起构造器扩十参：主数据读侧缓存补位（占用查询读侧归一，三段链路不触达） */
    @Mock
    private com.fuyun.pharmacy.cache.PharmacyMasterDataCache masterDataCache;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispenseItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), StockLedger.class);
    }

    @BeforeEach
    void loginOperator() {
        OperatorContextHolder.set("dispenser-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    private DispenseServiceImpl newService() {
        // 构造器十参直注（Task 6 起构造器承载 batchSelectService/events/objectMapper、Task 10 扩第十参 masterDataCache，
        // objectMapper 用真实例承载 JSON 读写）；ServiceImpl 继承字段 baseMapper 反射注入（Global Constraints 单测范式）
        DispenseServiceImpl impl = new DispenseServiceImpl(
                dispenseMapper,
                dispenseItemMapper,
                drugBatchMapper,
                stockLedgerMapper,
                prescriptionMapper,
                prescriptionItemMapper,
                batchSelectService,
                events,
                new ObjectMapper(),
                masterDataCache);
        ReflectionTestUtils.setField(impl, "baseMapper", dispenseMapper);
        return impl;
    }

    private Dispense dispense(String status) {
        Dispense d = new Dispense();
        d.setId(900L);
        d.setDispenseNo("D20260918000001");
        d.setRxNo("R20260918000001");
        d.setPatientId(700101L);
        d.setVisitId("O2026091800001");
        d.setStorehouse("OUTP_PHARM"); // pick 选批按单据库房（与 selectForDispense stub 同源，缺行则 stub 失配）
        d.setPicker("dispenser-01");
        d.setVerifier("reviewer-01");
        d.setStatus(status);
        return d;
    }

    private DispenseItem item(long id) {
        DispenseItem i = new DispenseItem();
        i.setId(1L);
        i.setDispenseId(900L);
        i.setPrescriptionItemId(10L);
        i.setDrugId(11L);
        i.setItemCode("C0131230900157");
        i.setRequestedQty(new BigDecimal("2"));
        i.setBatchId(55L);
        i.setBatchNo("B20260601");
        return i;
    }

    private DrugBatch batch() {
        DrugBatch b = new DrugBatch();
        b.setId(55L);
        b.setBatchNo("B20260601");
        b.setQuantity(new BigDecimal("50"));
        b.setLockedQty(BigDecimal.ZERO);
        return b;
    }

    private PickRequest pickRequest() {
        return new PickRequest(List.of(new PickLine("10", List.of("TR-A1B2", "TR-C3D4"))));
    }

    @Test
    @DisplayName("pick：FEFO 选批+条件锁定+追溯码逐码采集落行+单据转 PICKING")
    void pickLocksBatchAndCollectsTraceCodes() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(1);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "DISPENSING"))
                .thenReturn(1);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(item(1L)));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());
        when(drugBatchMapper.lockQuantity(55L, new BigDecimal("2"))).thenReturn(1);
        when(dispenseMapper.updateById(any(Dispense.class))).thenReturn(1);
        // 处方 id 回读（rx 100 号）供状态迁移断言
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        impl.pick("D20260918000001", pickRequest().items());

        verify(drugBatchMapper).lockQuantity(55L, new BigDecimal("2"));
        ArgumentCaptor<DispenseItem> itemCaptor = ArgumentCaptor.forClass(DispenseItem.class);
        verify(dispenseItemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getTraceCodes()).contains("TR-A1B2").contains("TR-C3D4");
        assertThat(itemCaptor.getValue().getBatchNo()).isEqualTo("B20260601");
    }

    @Test
    @DisplayName("pick 并发超发拒：锁定量不足条件更新 0 行 → PH-1010（事务回滚语义由调用方承载）")
    void pickRejectsWhenLockQuantumInsufficientAsPh1010() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(1);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "DISPENSING"))
                .thenReturn(1);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(item(1L)));
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());
        when(drugBatchMapper.lockQuantity(55L, new BigDecimal("2"))).thenReturn(0);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        assertThatThrownBy(() -> impl.pick("D20260918000001", pickRequest().items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.STOCK_INSUFFICIENT));
    }

    @Test
    @DisplayName("pick 单据状态并发被抢：casStatus(CREATED→PICKING) 0 行拒 PH-1009（处方侧与批次锁定零触碰）")
    void pickRejectsWhenDispenseCasAffectsZeroRowsAsPh1009() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(0);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx); // requireRx 先于双 CAS 定位处方

        assertThatThrownBy(() -> impl.pick("D20260918000001", pickRequest().items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(drugBatchMapper, never()).lockQuantity(anyLong(), any());
    }

    @Test
    @DisplayName("pick 处方状态违例：prescription casStatus(PENDING_DISPENSE→DISPENSING) 0 行拒 PH-1005（批次锁定零触碰）")
    void pickRejectsWhenPrescriptionCasAffectsZeroRowsAsPh1005() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(1);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "DISPENSING"))
                .thenReturn(0);

        assertThatThrownBy(() -> impl.pick("D20260918000001", pickRequest().items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED));
        verify(dispenseItemMapper, never()).selectList(any());
        verify(drugBatchMapper, never()).lockQuantity(anyLong(), any());
    }

    @Test
    @DisplayName("pick 采集缺行：采集面未覆盖发药明细行拒 PH-1006（400，逐行对应缺位即拒、选批锁定零触碰）")
    void pickRejectsMissingCollectLineAsPh1006() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(1);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "DISPENSING"))
                .thenReturn(1);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(item(1L)));
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        // 采集面仅携 prescriptionItemId=99，与明细行 10 不对应（orElseThrow 缺行分支）
        PickRequest missingLine = new PickRequest(List.of(new PickLine("99", List.of("TR-A1B2"))));

        assertThatThrownBy(() -> impl.pick("D20260918000001", missingLine.items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_LINE_INVALID));
        verify(drugBatchMapper, never()).lockQuantity(anyLong(), any());
    }

    @Test
    @DisplayName("pick 空追溯码：逐盒采集为空拒 PH-1006（「无码不结」合规口径，选批与锁定前置拦停）")
    void pickRejectsBlankTraceCodesAsPh1006() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(1);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "DISPENSING"))
                .thenReturn(1);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(item(1L)));
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        PickRequest blankTraces = new PickRequest(List.of(new PickLine("10", List.of())));

        assertThatThrownBy(() -> impl.pick("D20260918000001", blankTraces.items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_LINE_INVALID));
        verify(drugBatchMapper, never()).lockQuantity(anyLong(), any());
    }

    @Test
    @DisplayName("pick 缺处方：requireRx 按单据 rxNo 定位不到拒 PH-1004（404，双状态迁移零发生）")
    void pickRejectsWhenPrescriptionMissingAsPh1004() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(prescriptionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> impl.pick("D20260918000001", pickRequest().items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_NOT_FOUND));
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("verify 双签守卫：核对人=调配人拒 PH-1011（同一处方不得同一人双签，Spec :226）")
    void verifyRejectsSamePickerAsPh1011() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKING");
        d.setPicker("dispenser-01"); // 与 OperatorContextHolder 同账号
        when(dispenseMapper.selectOne(any())).thenReturn(d);

        assertThatThrownBy(() -> impl.verify("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DUAL_SIGN_CONFLICT));
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("verify 通过：PICKING→PICKED 且核对药师留痕（verify-02 与调配人互异——双签分权成链）")
    void verifyMarksPickedWithVerifier() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKING");
        d.setPicker("dispenser-01");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        when(dispenseMapper.casStatus(900L, "PICKING", "PICKED")).thenReturn(1);
        when(dispenseMapper.updateById(any(Dispense.class))).thenReturn(1);
        // 核对操作者切换第二账号（@BeforeEach 缺省 dispenser-01 会被 PH-1011 拒，须显式换人）
        OperatorContextHolder.set("verify-02");

        impl.verify("D20260918000001");

        verify(dispenseMapper).casStatus(900L, "PICKING", "PICKED");
        ArgumentCaptor<Dispense> captor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getVerifier()).isEqualTo("verify-02"); // 核对留痕=第二人
        assertThat(captor.getValue().getPicker()).isEqualTo("dispenser-01"); // 调配留痕不变（分权不改写）
    }

    @Test
    @DisplayName("verify 并发被抢：casStatus(PICKING→PICKED) 0 行拒 PH-1009（核对留痕零写面）")
    void verifyRejectsWhenCasAffectsZeroRowsAsPh1009() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKING");
        d.setPicker("dispenser-01");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        when(dispenseMapper.casStatus(900L, "PICKING", "PICKED")).thenReturn(0);
        OperatorContextHolder.set("verify-02"); // 换第二账号绕开 PH-1011 前置，专测 CAS 违例分支

        assertThatThrownBy(() -> impl.verify("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
        verify(dispenseMapper, never()).updateById(any(Dispense.class));
    }

    @Test
    @DisplayName("issue：批次扣减+出库流水（负数）+处方 DISPENSED+completed 事件携批次摘要")
    void issueDeductsBatchWritesLedgerAndPublishesCompleted() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKED");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        when(dispenseMapper.casIssue(org.mockito.ArgumentMatchers.eq(900L), anyString(), any(OffsetDateTime.class)))
                .thenReturn(1);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(item(1L)));
        when(drugBatchMapper.deductLocked(55L, new BigDecimal("2"))).thenReturn(1);
        when(prescriptionMapper.casStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        when(stockLedgerMapper.insert(any(StockLedger.class))).thenReturn(1);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        // 明细行追溯码回读（issue 时随事件携出）
        DispenseItem withTraces = item(1L);
        withTraces.setTraceCodes("[\"TR-A1B2\",\"TR-C3D4\"]");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(withTraces));
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);

        impl.issue("D20260918000001");

        verify(drugBatchMapper).deductLocked(55L, new BigDecimal("2"));
        ArgumentCaptor<StockLedger> ledgerCaptor = ArgumentCaptor.forClass(StockLedger.class);
        verify(stockLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getAction()).isEqualTo("ISSUE");
        assertThat(ledgerCaptor.getValue().getQuantity()).isEqualByComparingTo("-2");
        assertThat(ledgerCaptor.getValue().getRefDoc()).isEqualTo("D20260918000001");
        // 处方终态迁移 DISPENSED
        verify(prescriptionMapper).casStatus(100L, "DISPENSING", "DISPENSED");
        // completed 事件：批次摘要与追溯码逐码携出（Task 8 billing 占用回写载荷源）
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(eventCaptor.capture());
        com.fuyun.pharmacy.internal.PharmacyDomainEvent published =
                (com.fuyun.pharmacy.internal.PharmacyDomainEvent) eventCaptor.getValue();
        assertThat(published.eventType()).isEqualTo("pharmacy.dispense.completed");
        DispenseCompletedPayload payload = (DispenseCompletedPayload) published.payload();
        assertThat(payload.rxNo()).isEqualTo("R20260918000001");
        assertThat(payload.lines()).hasSize(1);
        assertThat(payload.lines().get(0).batchNo()).isEqualTo("B20260601");
        assertThat(payload.lines().get(0).traceCodes()).containsExactly("TR-A1B2", "TR-C3D4");
    }

    @Test
    @DisplayName("issue 状态守卫：非 PICKED 拒 PH-1009；发药人未留痕（缺核对）拒 PH-1011")
    void issueRejectsWrongStateAndMissingVerifier() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PICKING"));

        assertThatThrownBy(() -> impl.issue("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));

        Dispense noVerifier = dispense("PICKED");
        noVerifier.setVerifier(null);
        when(dispenseMapper.selectOne(any())).thenReturn(noVerifier);

        assertThatThrownBy(() -> impl.issue("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("配药缺单：未知 dispense_no 拒 PH-1008（404，requireByNo 守卫面）")
    void pickRejectsUnknownDispenseNoAsPh1008() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> impl.pick("D20260918999999", pickRequest().items()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_NOT_FOUND));
        verify(drugBatchMapper, never()).lockQuantity(anyLong(), any());
    }

    @Test
    @DisplayName("工作台回显：getByRxNo 组装 DispenseVO（单行面，数量 string 承载）")
    void getByRxNoReturnsAssembledVo() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        DispenseItem issued = item(1L);
        issued.setIssuedQty(new BigDecimal("2"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issued));

        com.fuyun.pharmacy.vo.DispenseVO vo = impl.getByRxNo("R20260918000001");

        assertThat(vo.rxNo()).isEqualTo("R20260918000001");
        assertThat(vo.status()).isEqualTo("ISSUED");
        assertThat(vo.items()).hasSize(1);
        assertThat(vo.items().get(0).requestedQuantity()).isEqualTo("2"); // DECIMAL string（D-18 口径）
    }

    @Test
    @DisplayName("工作台回显缺单：getByRxNo 无单返回 null（调用方提示未放行）")
    void getByRxNoReturnsNullWhenDispenseAbsent() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(null);

        assertThat(impl.getByRxNo("R20260918999999")).isNull();
    }

    @Test
    @DisplayName("追溯码脏数据：issue 读明细 traceCodes 非法 JSON 抛 IllegalStateException（禁静默吞码）")
    void issueRejectsCorruptTraceCodesAsIllegalState() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKED");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        when(dispenseMapper.casIssue(org.mockito.ArgumentMatchers.eq(900L), anyString(), any(OffsetDateTime.class)))
                .thenReturn(1);
        DispenseItem corrupt = item(1L);
        corrupt.setIssuedQty(new BigDecimal("2"));
        corrupt.setBatchId(55L);
        corrupt.setTraceCodes("not-a-json-array");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(corrupt));
        when(drugBatchMapper.deductLocked(55L, new BigDecimal("2"))).thenReturn(1);
        when(stockLedgerMapper.insert(any(StockLedger.class))).thenReturn(1);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        assertThatThrownBy(() -> impl.issue("D20260918000001")).isInstanceOf(IllegalStateException.class);
        verify(events, never()).publishEvent(any()); // 脏数据不出 completed 事件
    }

    @Test
    @DisplayName("issue 发药签名并发被抢：casIssue 0 行拒 PH-1009（明细/处方/事件零触碰）")
    void issueRejectsWhenSignCasAffectsZeroRowsAsPh1009() {
        DispenseServiceImpl impl = newService();
        // dispense("PICKED") 助手自带核对留痕（verifier≠picker），双签前置天然通过，专测签名 CAS 分支
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PICKED"));
        when(dispenseMapper.casIssue(org.mockito.ArgumentMatchers.eq(900L), anyString(), any(OffsetDateTime.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> impl.issue("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
        verify(dispenseItemMapper, never()).selectList(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("issue 未锁先发违例：deductLocked 0 行拒 PH-1010（流水/处方收敛/事件零触碰，整事务回滚）")
    void issueRejectsWhenDeductLockedAffectsZeroRowsAsPh1010() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PICKED"));
        when(dispenseMapper.casIssue(org.mockito.ArgumentMatchers.eq(900L), anyString(), any(OffsetDateTime.class)))
                .thenReturn(1);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(item(1L)));
        when(drugBatchMapper.deductLocked(55L, new BigDecimal("2"))).thenReturn(0);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        assertThatThrownBy(() -> impl.issue("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.STOCK_INSUFFICIENT));
        verify(stockLedgerMapper, never()).insert(any(StockLedger.class));
        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("issue 处方收敛并发被抢：DISPENSING→DISPENSED CAS 0 行拒 PH-1005（completed 事件后置锚、违例禁出）")
    void issueRejectsWhenPrescriptionCasAffectsZeroRowsAsPh1005() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PICKED"));
        when(dispenseMapper.casIssue(org.mockito.ArgumentMatchers.eq(900L), anyString(), any(OffsetDateTime.class)))
                .thenReturn(1);
        DispenseItem withTraces = item(1L);
        withTraces.setTraceCodes("[\"TR-A1B2\",\"TR-C3D4\"]"); // 行摘要汇总在处方 CAS 之前，须合法 JSON 过 fromJson
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(withTraces));
        when(drugBatchMapper.deductLocked(55L, new BigDecimal("2"))).thenReturn(1);
        when(stockLedgerMapper.insert(any(StockLedger.class))).thenReturn(1);
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "DISPENSED")).thenReturn(0);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        assertThatThrownBy(() -> impl.issue("D20260918000001"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED));
        verify(events, never()).publishEvent(any());
    }
}
