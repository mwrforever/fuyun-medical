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
import com.baomidou.mybatisplus.extension.toolkit.Db;
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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
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

    /** Task 11 起构造器扩十一参：结算单反查端口补位（verify 凭证核验消费方，三段守卫用例传 null 不触达） */
    @Mock
    private com.fuyun.billing.api.SettlementQueryPort settlementQueryPort;

    /** P2 PR-3 Task 8 起构造器扩十二参：住院摆药计划服务补位（三段链路不触达住院分流） */
    @Mock
    private com.fuyun.pharmacy.service.IDispensePlanService dispensePlanService;

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
        // 构造器十参直注（Task 6 起构造器承载 batchSelectService/events/objectMapper、Task 10 扩第十参
        // masterDataCache、Task 11 扩第十一参 settlementQueryPort、EX-37 收敛十参——流水批插改 Db
        // 通道后 StockLedgerMapper 依赖卸除；objectMapper 用真实例承载 JSON 读写）；
        // ServiceImpl 继承字段 baseMapper 反射注入（Global Constraints 单测范式）
        DispenseServiceImpl impl = new DispenseServiceImpl(
                dispenseMapper,
                dispenseItemMapper,
                drugBatchMapper,
                prescriptionMapper,
                prescriptionItemMapper,
                batchSelectService,
                events,
                new ObjectMapper(),
                masterDataCache,
                settlementQueryPort,
                dispensePlanService);
        ReflectionTestUtils.setField(impl, "baseMapper", dispenseMapper);
        // 链式查询载体：Mockito 桩 mapper 非 MyBatis 真代理，entityClass 须直设（billing/inpatient 同款）
        ReflectionTestUtils.setField(impl, "entityClass", Dispense.class);
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

        // EX-37 桩面通道迁移：明细回填通道从逐行 updateById 改 Db.updateBatchById 批更（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.pick("D20260918000001", pickRequest().items());

            verify(drugBatchMapper).lockQuantity(55L, new BigDecimal("2"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(itemCaptor.capture()));
            assertThat(itemCaptor.getValue().get(0).getTraceCodes())
                    .contains("TR-A1B2")
                    .contains("TR-C3D4");
            assertThat(itemCaptor.getValue().get(0).getBatchNo()).isEqualTo("B20260601");
        }
        // 留痕实体状态与 CAS 迁移终态同步（禁携带 CAS 前旧状态落库覆写状态机——PR-4 IT 实证缺陷回归守卫）
        ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).updateById(dispenseCaptor.capture());
        assertThat(dispenseCaptor.getValue().getStatus()).isEqualTo("PICKING");
        assertThat(dispenseCaptor.getValue().getPicker()).isEqualTo("dispenser-01");
    }

    @Test
    @DisplayName("pick 批量写锚定（EX-37）：两行回填一次批更（N 行 N 更收敛 1 批）+ 补丁仅携 id+批次三列 " + "+ 批次锁定 CAS 逐行保留（0 行并发防线禁批量吞语义）")
    void pickBatchesItemBackfillPatchOnceWithLockCasKeptPerRow() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));
        when(dispenseMapper.casStatus(900L, "CREATED", "PICKING")).thenReturn(1);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "DISPENSING"))
                .thenReturn(1);
        DispenseItem first = item(1L); // id=1/prescriptionItemId=10/qty=2/批次 55
        DispenseItem second = new DispenseItem();
        second.setId(2L);
        second.setDispenseId(900L);
        second.setPrescriptionItemId(20L);
        second.setDrugId(11L);
        second.setItemCode("C0131230900158");
        second.setRequestedQty(new BigDecimal("3"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(first, second));
        DrugBatch secondBatch = batch();
        secondBatch.setId(66L);
        secondBatch.setBatchNo("B20260701");
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("2")))
                .thenReturn(batch());
        when(batchSelectService.selectForDispense(11L, "OUTP_PHARM", new BigDecimal("3")))
                .thenReturn(secondBatch);
        when(drugBatchMapper.lockQuantity(55L, new BigDecimal("2"))).thenReturn(1);
        when(drugBatchMapper.lockQuantity(66L, new BigDecimal("3"))).thenReturn(1);
        when(dispenseMapper.updateById(any(Dispense.class))).thenReturn(1);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.pick(
                    "D20260918000001",
                    List.of(new PickLine("10", List.of("TR-A1B2", "TR-C3D4")), new PickLine("20", List.of("TR-E5F6"))));

            // 批次锁定条件更新逐行保留（0 行=并发超发硬防线，选批依赖前序锁定状态禁批量化）
            verify(drugBatchMapper).lockQuantity(55L, new BigDecimal("2"));
            verify(drugBatchMapper).lockQuantity(66L, new BigDecimal("3"));
            // 明细批次/追溯码回填一次批更（A.4.3-16），旧逐行 updateById 通道下线
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> patchesCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(patchesCaptor.capture()));
            verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
            List<DispenseItem> patches = patchesCaptor.getValue();
            assertThat(patches).hasSize(2);
            assertThat(patches.get(0).getId()).isEqualTo(1L);
            assertThat(patches.get(0).getBatchId()).isEqualTo(55L);
            assertThat(patches.get(0).getBatchNo()).isEqualTo("B20260601");
            assertThat(patches.get(0).getTraceCodes()).contains("TR-A1B2").contains("TR-C3D4");
            assertThat(patches.get(1).getId()).isEqualTo(2L);
            assertThat(patches.get(1).getBatchNo()).isEqualTo("B20260701");
            // EX-24 补丁纪律：仅携 id+批次三列，读点快照列（应发数/明细状态/药品等）不进 SET——
            //   跨队乱序窗口内对端写面（退药累计回写等）不被读点快照覆写吞掉
            assertThat(patches).allSatisfy(patch -> {
                assertThat(patch.getDispenseId()).isNull();
                assertThat(patch.getPrescriptionItemId()).isNull();
                assertThat(patch.getDrugId()).isNull();
                assertThat(patch.getItemCode()).isNull();
                assertThat(patch.getRequestedQty()).isNull();
                assertThat(patch.getIssuedQty()).isNull();
                assertThat(patch.getReturnedQty()).isNull();
                assertThat(patch.getItemStatus()).isNull();
            });
        }
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

        assertThatThrownBy(() -> impl.verify("D20260918000001", null))
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

        impl.verify("D20260918000001", null); // 无凭证帧：跳过归属核验，追溯码主道不变

        verify(dispenseMapper).casStatus(900L, "PICKING", "PICKED");
        ArgumentCaptor<Dispense> captor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getVerifier()).isEqualTo("verify-02"); // 核对留痕=第二人
        // EX-24 断言现代化（D-21 回归红线出口，逐次批准单点单次）：原「picker 等值断言」冻结整行
        // 回写实现细节（以携读点快照同值落库作为「分权不改写」的可观测面）；指定列补丁回写下
        // 「不改写调配留痕」由 picker 列不进 SET 承载，isNull 为更严格契约——断言业务意图不变且强化
        assertThat(captor.getValue().getPicker()).isNull(); // 调配留痕列不携带（分权不改写）
        // 留痕实体状态与 CAS 迁移终态同步（禁携带 CAS 前旧状态落库覆写状态机——PR-4 IT 实证缺陷回归守卫）
        assertThat(captor.getValue().getStatus()).isEqualTo("PICKED");
    }

    @Test
    @DisplayName("pick 留痕并发防覆写（EX-24）：单据头补丁回写仅携 id+状态同值+调配人，读点快照列不进 SET")
    void pickWritesDispenseHeaderPatchWithoutSnapshotColumns() {
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
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        // EX-37 桩面补位：明细回填改 Db.updateBatchById 批更（静态 Db 桩内执行；靶点仍为单据头补丁列集）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.pick("D20260918000001", pickRequest().items());

            ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
            verify(dispenseMapper).updateById(dispenseCaptor.capture());
            Dispense saved = dispenseCaptor.getValue();
            // 目标面精确：id 定位 + 状态同值（上方 CAS 已置 PICKING，补写同值维持内存/DB 同步纪律）+ 调配人留痕
            assertThat(saved.getId()).isEqualTo(900L);
            assertThat(saved.getStatus()).isEqualTo("PICKING");
            assertThat(saved.getPicker()).isEqualTo("dispenser-01");
            // 非目标列零携带：NOT_NULL 更新策略下 null 不进 SET——读改写窗口内对端写面（状态机 CAS
            //   列更新、核对/发药留痕通道）对单号/处方号/患者/库房/核对发药列的提交不被读点快照覆写吞掉
            assertThat(saved.getDispenseNo()).isNull();
            assertThat(saved.getRxNo()).isNull();
            assertThat(saved.getPatientId()).isNull();
            assertThat(saved.getVisitId()).isNull();
            assertThat(saved.getStorehouse()).isNull();
            assertThat(saved.getVerifier()).isNull();
            assertThat(saved.getIssuer()).isNull();
        }
    }

    @Test
    @DisplayName("verify 留痕并发防覆写（EX-24）：单据头补丁回写仅携 id+状态同值+核对人，调配留痕等快照列不进 SET")
    void verifyWritesDispenseHeaderPatchWithoutSnapshotColumns() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKING");
        d.setPicker("dispenser-01");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        when(dispenseMapper.casStatus(900L, "PICKING", "PICKED")).thenReturn(1);
        when(dispenseMapper.updateById(any(Dispense.class))).thenReturn(1);
        OperatorContextHolder.set("verify-02"); // 换第二账号绕开 PH-1011 前置（双签分权）

        impl.verify("D20260918000001", null);

        ArgumentCaptor<Dispense> captor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).updateById(captor.capture());
        Dispense saved = captor.getValue();
        // 目标面精确：id 定位 + 状态同值（上方 CAS 已置 PICKED）+ 核对人留痕
        assertThat(saved.getId()).isEqualTo(900L);
        assertThat(saved.getStatus()).isEqualTo("PICKED");
        assertThat(saved.getVerifier()).isEqualTo("verify-02");
        // 非目标列零携带：调配留痕（picker）在补丁形态下一并不进 SET——「verify 不改写调配留痕」
        //   由列不携带承载（禁同值覆写形态），读改写窗口内对端提交不被吞
        assertThat(saved.getPicker()).isNull();
        assertThat(saved.getDispenseNo()).isNull();
        assertThat(saved.getRxNo()).isNull();
        assertThat(saved.getPatientId()).isNull();
        assertThat(saved.getStorehouse()).isNull();
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

        assertThatThrownBy(() -> impl.verify("D20260918000001", null))
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
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        // 明细行追溯码回读（issue 时随事件携出）
        DispenseItem withTraces = item(1L);
        withTraces.setTraceCodes("[\"TR-A1B2\",\"TR-C3D4\"]");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(withTraces));

        // EX-37 桩面通道迁移：出库流水从逐行 insert 改 Db.saveBatch 批插、实发回写从逐行
        //   updateById 改 Db.updateBatchById 批更（静态 Db 桩内执行；批插/批更桩默认打桩移除）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.issue("D20260918000001");

            verify(drugBatchMapper).deductLocked(55L, new BigDecimal("2"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StockLedger>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.saveBatch(ledgerCaptor.capture()));
            assertThat(ledgerCaptor.getValue().get(0).getAction()).isEqualTo("ISSUE");
            assertThat(ledgerCaptor.getValue().get(0).getQuantity()).isEqualByComparingTo("-2");
            assertThat(ledgerCaptor.getValue().get(0).getRefDoc()).isEqualTo("D20260918000001");
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
    }

    @Test
    @DisplayName("issue 批量写锚定（EX-37）：流水一次批插+实发回写一次批更（N 行 2N 写收敛 2 批）+ 扣减 CAS 逐行保留 " + "+ 实发补丁仅携 id+issuedQty")
    void issueBatchesLedgerInsertAndIssuedQtyPatchWithDeductCasKeptPerRow() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKED");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        when(dispenseMapper.casIssue(org.mockito.ArgumentMatchers.eq(900L), anyString(), any(OffsetDateTime.class)))
                .thenReturn(1);
        DispenseItem first = item(1L); // id=1/batchId=55/qty=2
        first.setTraceCodes("[\"TR-A1B2\",\"TR-C3D4\"]");
        DispenseItem second = new DispenseItem();
        second.setId(2L);
        second.setDispenseId(900L);
        second.setPrescriptionItemId(20L);
        second.setDrugId(11L);
        second.setItemCode("C0131230900158");
        second.setRequestedQty(new BigDecimal("3"));
        second.setBatchId(66L);
        second.setBatchNo("B20260701");
        second.setTraceCodes("[\"TR-E5F6\"]");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(first, second));
        // 扣减条件更新逐行保留：0 行=未锁先发违例硬防线（禁批量吞 CAS 语义）
        when(drugBatchMapper.deductLocked(55L, new BigDecimal("2"))).thenReturn(1);
        when(drugBatchMapper.deductLocked(66L, new BigDecimal("3"))).thenReturn(1);
        when(prescriptionMapper.casStatus(anyLong(), anyString(), anyString())).thenReturn(1);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.issue("D20260918000001");

            verify(drugBatchMapper).deductLocked(55L, new BigDecimal("2"));
            verify(drugBatchMapper).deductLocked(66L, new BigDecimal("3"));
            // 出库流水一次批插（红线 2 勾稽行集与行序不变，ASSIGN_ID 自动填充），旧逐行 insert 通道下线
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StockLedger>> ledgersCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.saveBatch(ledgersCaptor.capture()));
            assertThat(ledgersCaptor.getValue()).hasSize(2);
            assertThat(ledgersCaptor.getValue()).allSatisfy(ledger -> {
                assertThat(ledger.getAction()).isEqualTo("ISSUE");
                assertThat(ledger.getRefDoc()).isEqualTo("D20260918000001");
                assertThat(ledger.getQuantity()).isNegative(); // 负数量出库行
            });
            // 实发回写一次批更（A.4.3-16），旧逐行 updateById 通道下线
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> patchesCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(patchesCaptor.capture()));
            verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
            assertThat(patchesCaptor.getValue()).hasSize(2);
            assertThat(patchesCaptor.getValue().get(0).getId()).isEqualTo(1L);
            assertThat(patchesCaptor.getValue().get(0).getIssuedQty()).isEqualByComparingTo("2");
            assertThat(patchesCaptor.getValue().get(1).getId()).isEqualTo(2L);
            assertThat(patchesCaptor.getValue().get(1).getIssuedQty()).isEqualByComparingTo("3");
            // EX-24 补丁纪律：仅携 id+实发数，读点快照列（批次/追溯码/应发数等）不进 SET
            assertThat(patchesCaptor.getValue()).allSatisfy(patch -> {
                assertThat(patch.getDispenseId()).isNull();
                assertThat(patch.getPrescriptionItemId()).isNull();
                assertThat(patch.getDrugId()).isNull();
                assertThat(patch.getRequestedQty()).isNull();
                assertThat(patch.getBatchId()).isNull();
                assertThat(patch.getBatchNo()).isNull();
                assertThat(patch.getTraceCodes()).isNull();
                assertThat(patch.getItemStatus()).isNull();
            });
        }
    }

    @Test
    @DisplayName("issue 状态守卫：非 PICKED 拒 PH-1009；发药人未留痕（缺核对）一并拒 PH-1009")
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

        // EX-37 桩面通道迁移：零流水断言从旧 insert 通道迁至 Db.saveBatch 批插通道（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.issue("D20260918000001"))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.STOCK_INSUFFICIENT));
            mockedDb.verify(() -> Db.saveBatch(any()), never());
            verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
            verify(events, never()).publishEvent(any());
        }
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
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "DISPENSED")).thenReturn(0);
        com.fuyun.pharmacy.entity.Prescription rx = new com.fuyun.pharmacy.entity.Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        // EX-37 桩面补位：流水/实发批写在处方 CAS 前已发生（真实库随 CAS 违例整事务回滚），
        //   静态 Db 桩内执行；insert/updateById 旧通道桩移除
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.issue("D20260918000001"))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.PRESCRIPTION_STATE_NOT_ALLOWED));
            verify(events, never()).publishEvent(any());
        }
    }
}
