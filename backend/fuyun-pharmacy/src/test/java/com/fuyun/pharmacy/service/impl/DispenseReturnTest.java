package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.DispenseReturnedPayload;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.StockLedger;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 退药受理与终态单测：实物退（追溯码核验/批次回补/流水冲正/处方明细退药累计回写/单据终态/
 * returned 事件）、发药中明细退场（释放锁定不落流水）、refund.approved 按单据清单终态镜像与幂等
 * （PR-5 Task 11 单据化收口——confirmRefundTerminalByRx 逐 rxNo 精确收敛，注记⑦误伤面闭合）。
 */
@ExtendWith(MockitoExtension.class)
class DispenseReturnTest {

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

    /** Task 10 起构造器扩十参：主数据读侧缓存补位（占用查询读侧归一，退药链路不触达） */
    @Mock
    private com.fuyun.pharmacy.cache.PharmacyMasterDataCache masterDataCache;

    /** Task 11 起构造器扩十一参：结算单反查端口补位（verify 凭证核验消费方，退药链路不触达） */
    @Mock
    private com.fuyun.billing.api.SettlementQueryPort settlementQueryPort;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispenseItem.class);
        // OPT-09 批查化起退费终态确认走 Prescription lambda IN 批查（列名解析依赖 TableInfo）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Prescription.class);
    }

    @BeforeEach
    void loginOperator() {
        OperatorContextHolder.set("returner-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    private DispenseServiceImpl newService() {
        // 构造器十参直注（Task 6 起扩 batchSelectService/events/objectMapper、Task 10 扩第十参
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
                settlementQueryPort);
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
        d.setStorehouse("OUTP_PHARM");
        d.setStatus(status);
        return d;
    }

    private DispenseItem issuedItem(String issued, String returned, String traces) {
        DispenseItem i = new DispenseItem();
        i.setId(1L);
        i.setDispenseId(900L);
        i.setPrescriptionItemId(10L);
        i.setDrugId(11L);
        i.setItemCode("C0131230900157");
        i.setRequestedQty(new BigDecimal(issued));
        i.setIssuedQty(new BigDecimal(issued));
        i.setReturnedQty(new BigDecimal(returned));
        i.setBatchId(55L);
        i.setBatchNo("B20260601");
        i.setTraceCodes(traces);
        return i;
    }

    private DispenseReturnRequest issuedReturn(String qty, List<String> traces) {
        return new DispenseReturnRequest(
                "D20260918000001", "ISSUED_RETURN", List.of(new DispenseReturnRequest.ReturnLine("10", qty, traces)));
    }

    /** 退费终态确认用处方构造（混合状态面：DISPENSED/PENDING_DISPENSE 等按用例指定） */
    private Prescription terminalRx(long id, String rxNo, String status) {
        Prescription rx = new Prescription();
        rx.setId(id);
        rx.setRxNo(rxNo);
        rx.setStatus(status);
        return rx;
    }

    @Test
    @DisplayName("实物退全量：追溯码核验通过→批次回补+回补流水（正数）→发药单 FULL_RETURNED→returned 事件 fullReturn=true")
    void acceptReturnRestocksBatchAndMarksFullReturned() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("2"))).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "ISSUED", "FULL_RETURNED")).thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("2")))
                .thenReturn(1);

        // EX-37 桩面通道迁移：回补流水从逐行 insert 改 Db.saveBatch 批插、退药回写从逐行
        //   updateById 改 Db.updateBatchById 批更（静态 Db 桩内执行；旧通道桩移除）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptReturn(issuedReturn("2", List.of("TR-A1B2", "TR-C3D4")));

            verify(drugBatchMapper).restock(55L, new BigDecimal("2"));
            // 数据库写操作断言：处方明细退药累计回写（V701 returned_quantity 落地点，occupancy 数据源）
            verify(prescriptionItemMapper).accumulateReturnedQuantity(10L, new BigDecimal("2"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StockLedger>> ledgerCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.saveBatch(ledgerCaptor.capture()));
            assertThat(ledgerCaptor.getValue().get(0).getAction()).isEqualTo("RETURN_RESTOCK");
            assertThat(ledgerCaptor.getValue().get(0).getQuantity()).isEqualByComparingTo("2");
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(eventCaptor.capture());
            DispenseReturnedPayload payload = (DispenseReturnedPayload)
                    ((com.fuyun.pharmacy.internal.PharmacyDomainEvent) eventCaptor.getValue()).payload();
            assertThat(payload.fullReturn()).isTrue();
            // 携退药行摘要（id 29 desc 冻结 lines 非空——billing 占用回退/退费联动读此面）
            assertThat(payload.lines()).hasSize(1);
            assertThat(payload.lines().get(0).itemCode()).isEqualTo("C0131230900157");
            assertThat(payload.lines().get(0).batchNo()).isEqualTo("B20260601");
            assertThat(payload.lines().get(0).quantity()).isEqualTo("2");
        }
    }

    @Test
    @DisplayName("实物退批量写锚定（EX-37）：回补流水一次批插+退药回写一次批更（N 行 2N 写收敛 2 批） " + "+ 回补/明细累计 0 行防线逐行保留 + 退药补丁仅携 id+returnedQty")
    void acceptReturnBatchesLedgerInsertAndReturnedQtyPatchWithRowGuardsKeptPerRow() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        DispenseItem first = issuedItem("2", "0", "[\"TR-A1B2\"]"); // id=1/prescriptionItemId=10/batchId=55
        DispenseItem second = new DispenseItem();
        second.setId(2L);
        second.setDispenseId(900L);
        second.setPrescriptionItemId(20L);
        second.setDrugId(11L);
        second.setItemCode("C0131230900158");
        second.setRequestedQty(new BigDecimal("3"));
        second.setIssuedQty(new BigDecimal("3"));
        second.setReturnedQty(BigDecimal.ZERO);
        second.setBatchId(66L);
        second.setBatchNo("B20260701");
        second.setTraceCodes("[\"TR-E5F6\"]");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(first, second));
        // 回补条件更新逐行保留：0 行=批次状态漂移硬防线（禁批量吞语义）
        when(drugBatchMapper.restock(55L, new BigDecimal("2"))).thenReturn(1);
        when(drugBatchMapper.restock(66L, new BigDecimal("3"))).thenReturn(1);
        // 处方明细累计回写逐行保留：0 行=明细脏数据硬防线（服务端原子累加语义禁批量化）
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("2")))
                .thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(20L, new BigDecimal("3")))
                .thenReturn(1);
        when(dispenseMapper.casStatus(900L, "ISSUED", "FULL_RETURNED")).thenReturn(1);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptReturn(new DispenseReturnRequest(
                    "D20260918000001",
                    "ISSUED_RETURN",
                    List.of(
                            new DispenseReturnRequest.ReturnLine("10", "2", List.of("TR-A1B2")),
                            new DispenseReturnRequest.ReturnLine("20", "3", List.of("TR-E5F6")))));

            verify(drugBatchMapper).restock(55L, new BigDecimal("2"));
            verify(drugBatchMapper).restock(66L, new BigDecimal("3"));
            verify(prescriptionItemMapper).accumulateReturnedQuantity(10L, new BigDecimal("2"));
            verify(prescriptionItemMapper).accumulateReturnedQuantity(20L, new BigDecimal("3"));
            // 回补流水一次批插（红线 2 勾稽行集不变，正数回补行），旧逐行 insert 通道下线
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StockLedger>> ledgersCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.saveBatch(ledgersCaptor.capture()));
            assertThat(ledgersCaptor.getValue()).hasSize(2);
            assertThat(ledgersCaptor.getValue()).allSatisfy(ledger -> {
                assertThat(ledger.getAction()).isEqualTo("RETURN_RESTOCK");
                assertThat(ledger.getRefDoc()).isEqualTo("D20260918000001");
                assertThat(ledger.getQuantity()).isPositive(); // 正数量回补行
            });
            // 退药回写一次批更（A.4.3-16），旧逐行 updateById 通道下线
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> patchesCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(patchesCaptor.capture()));
            verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
            assertThat(patchesCaptor.getValue()).hasSize(2);
            assertThat(patchesCaptor.getValue().get(0).getId()).isEqualTo(1L);
            assertThat(patchesCaptor.getValue().get(0).getReturnedQty()).isEqualByComparingTo("2");
            assertThat(patchesCaptor.getValue().get(1).getId()).isEqualTo(2L);
            assertThat(patchesCaptor.getValue().get(1).getReturnedQty()).isEqualByComparingTo("3");
            // EX-24 补丁纪律：仅携 id+退药数，读点快照列（批次/追溯码/实发数等）不进 SET——
            //   跨队乱序窗口内对端写面（issued 回写等）不被读点快照覆写吞掉
            assertThat(patchesCaptor.getValue()).allSatisfy(patch -> {
                assertThat(patch.getDispenseId()).isNull();
                assertThat(patch.getPrescriptionItemId()).isNull();
                assertThat(patch.getDrugId()).isNull();
                assertThat(patch.getItemCode()).isNull();
                assertThat(patch.getRequestedQty()).isNull();
                assertThat(patch.getIssuedQty()).isNull();
                assertThat(patch.getBatchId()).isNull();
                assertThat(patch.getBatchNo()).isNull();
                assertThat(patch.getTraceCodes()).isNull();
                assertThat(patch.getItemStatus()).isNull();
            });
        }
    }

    @Test
    @DisplayName("防回流药：追溯码与发药采集记录不一致拒 PH-1012（零回补零流水）")
    void acceptReturnRejectsTraceCodeMismatchAsPh1012() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("1", List.of("TR-FAKE"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.TRACE_CODE_MISMATCH));
        verify(drugBatchMapper, never()).restock(anyLong(), any());
    }

    @Test
    @DisplayName("部分退：发药单 PART_RETURNED、returned 事件 fullReturn=false（R2-13 时点①）")
    void acceptReturnPartialMarksPartReturned() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "ISSUED", "PART_RETURNED")).thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("1")))
                .thenReturn(1);

        // EX-37 桩面补位：退药回写改 Db.updateBatchById 批更（静态 Db 桩内执行；旧通道桩移除）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptReturn(issuedReturn("1", List.of("TR-A1B2")));

            verify(dispenseMapper).casStatus(900L, "ISSUED", "PART_RETURNED");
            // 部分退时点回写断言：回写参数=本次退量（服务端原子累加，累计形态由 SQL 侧 + 承载）
            verify(prescriptionItemMapper).accumulateReturnedQuantity(10L, new BigDecimal("1"));
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(eventCaptor.capture());
            DispenseReturnedPayload payload = (DispenseReturnedPayload)
                    ((com.fuyun.pharmacy.internal.PharmacyDomainEvent) eventCaptor.getValue()).payload();
            assertThat(payload.fullReturn()).isFalse();
            assertThat(payload.lines()).hasSize(1); // 部分退同样携退药行摘要（非空，与 id 29 desc 对齐）
            assertThat(payload.lines().get(0).quantity()).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("部分退同样携退药行摘要后：PART_RETURNED 续退余量→处方明细退药累计再次按增量回写")
    void acceptReturnSecondPartialAccumulatesReturnedQuantityIncrementally() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PART_RETURNED"));
        // 既往已退 1/实发 2：可退余额 1，本次续退 1（同批次剩码）
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "1", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "PART_RETURNED", "FULL_RETURNED")).thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("1")))
                .thenReturn(1);

        // EX-37 桩面补位：退药回写改 Db.updateBatchById 批更（静态 Db 桩内执行；旧通道桩移除）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptReturn(issuedReturn("1", List.of("TR-C3D4")));

            // 累计形态断言：续退时点再次按增量回写（returned_quantity = returned_quantity + 本次退量），
            //   两次部分退累计 = 全量——occupancy returnedQuantity 由此收敛到实发数
            verify(prescriptionItemMapper).accumulateReturnedQuantity(10L, new BigDecimal("1"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(itemCaptor.capture()));
            assertThat(itemCaptor.getValue().get(0).getReturnedQty()).isEqualByComparingTo("2");
        }
    }

    @Test
    @DisplayName("处方明细回写零行：accumulateReturnedQuantity 0 行拒 PH-1013（明细脏数据整事务回滚）")
    void acceptReturnRejectsWhenRxItemAccumulateAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(1);
        // EX-37 桩面迁移：旧 updateById 桩移除（0 行回写违例在批写前抛出，批通道不触达）
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("1")))
                .thenReturn(0);

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("1", List.of("TR-A1B2"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        // 回写失败即整事务回滚（批次回补/明细回写随之作废）：终态迁移与 returned 事件禁出
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("发药中明细退场：释放锁定批次（不落流水不发事件）且明细退场（R2-13 时点②）")
    void dispensingCancelReleasesLockWithoutLedger() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKING");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        DispenseItem picking = issuedItem("2", "0", "[]");
        picking.setIssuedQty(BigDecimal.ZERO);
        picking.setBatchId(55L);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(picking));
        when(drugBatchMapper.releaseLock(55L, new BigDecimal("2"))).thenReturn(1);

        // EX-37 桩面通道迁移：明细退场从逐行 updateById 改 Db.updateBatchById 批更（静态 Db 桩
        //   内执行；旧通道桩移除）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptReturn(new DispenseReturnRequest(
                    "D20260918000001",
                    "DISPENSING_CANCEL",
                    List.of(new DispenseReturnRequest.ReturnLine("10", "2", null))));

            verify(drugBatchMapper).releaseLock(55L, new BigDecimal("2"));
            mockedDb.verify(() -> Db.saveBatch(any()), never()); // 锁定数非数量流水（批插通道零流水）
            verify(events, never()).publishEvent(any()); // 未出库不发 returned
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(itemCaptor.capture()));
            assertThat(itemCaptor.getValue().get(0).getItemStatus()).isEqualTo("CANCELLED");
        }
    }

    @Test
    @DisplayName("发药中退场批量写锚定（EX-37）：明细退场一次批更（N 行 N 更收敛 1 批）+ 锁定释放 0 行防线逐行保留 " + "+ 退场补丁仅携 id+itemStatus")
    void dispensingCancelBatchesItemPatchWithReleaseLockKeptPerRow() {
        DispenseServiceImpl impl = newService();
        Dispense d = dispense("PICKING");
        when(dispenseMapper.selectOne(any())).thenReturn(d);
        DispenseItem first = issuedItem("2", "0", "[]");
        first.setIssuedQty(BigDecimal.ZERO);
        first.setBatchId(55L);
        DispenseItem second = new DispenseItem();
        second.setId(2L);
        second.setDispenseId(900L);
        second.setPrescriptionItemId(20L);
        second.setDrugId(11L);
        second.setItemCode("C0131230900158");
        second.setRequestedQty(new BigDecimal("3"));
        second.setIssuedQty(BigDecimal.ZERO);
        second.setBatchId(66L);
        second.setBatchNo("B20260701");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(first, second));
        // 锁定释放条件更新逐行保留：0 行=锁定数漂移硬防线（禁批量吞语义）
        when(drugBatchMapper.releaseLock(55L, new BigDecimal("2"))).thenReturn(1);
        when(drugBatchMapper.releaseLock(66L, new BigDecimal("3"))).thenReturn(1);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.acceptReturn(new DispenseReturnRequest(
                    "D20260918000001",
                    "DISPENSING_CANCEL",
                    List.of(
                            new DispenseReturnRequest.ReturnLine("10", "2", null),
                            new DispenseReturnRequest.ReturnLine("20", "3", null))));

            verify(drugBatchMapper).releaseLock(55L, new BigDecimal("2"));
            verify(drugBatchMapper).releaseLock(66L, new BigDecimal("3"));
            // 明细退场一次批更（A.4.3-16），旧逐行 updateById 通道下线；锁定数非数量流水零批插
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> patchesCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(patchesCaptor.capture()));
            verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
            mockedDb.verify(() -> Db.saveBatch(any()), never());
            assertThat(patchesCaptor.getValue()).hasSize(2);
            // EX-24 补丁纪律：仅携 id+明细状态，读点快照列（批次/数量等）不进 SET
            assertThat(patchesCaptor.getValue()).allSatisfy(patch -> {
                assertThat(patch.getItemStatus()).isEqualTo("CANCELLED");
                assertThat(patch.getDispenseId()).isNull();
                assertThat(patch.getPrescriptionItemId()).isNull();
                assertThat(patch.getDrugId()).isNull();
                assertThat(patch.getRequestedQty()).isNull();
                assertThat(patch.getBatchId()).isNull();
                assertThat(patch.getBatchNo()).isNull();
                assertThat(patch.getTraceCodes()).isNull();
            });
        }
    }

    @Test
    @DisplayName("refund.approved 单据化镜像：同患者两 DISPENSED 处方仅反查清单内处方镜像 FULL_RETURNED（注记⑦收口）")
    void confirmRefundTerminalByRxMirrorsOnlyListedRx() {
        DispenseServiceImpl impl = newService();
        // 同患者两处方均 DISPENSED 且发药单均已 FULL_RETURNED——本次反查清单仅覆盖其一
        // （批查键集=清单单号，未列入处方不出结果集即零触达）
        Prescription listed = new Prescription();
        listed.setId(100L);
        listed.setRxNo("R20260918000001");
        listed.setPatientId(700101L);
        listed.setStatus("DISPENSED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(listed));
        when(dispenseMapper.selectList(any())).thenReturn(List.of(dispense("FULL_RETURNED")));
        when(prescriptionMapper.casStatus(100L, "DISPENSED", "FULL_RETURNED")).thenReturn(1);

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        // 单据精确核心断言：仅清单内处方镜像，未列入处方零迁移（患者维度误伤面回归锚）
        verify(prescriptionMapper).casStatus(100L, "DISPENSED", "FULL_RETURNED");
        verify(prescriptionMapper, never()).casStatus(eq(101L), anyString(), anyString());
    }

    @Test
    @DisplayName("refund.approved 幂等：处方已退药终态重读跳过（双通道收敛仅终态生效一次）")
    void confirmRefundTerminalByRxIsIdempotentWhenAlreadyReturned() {
        DispenseServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setStatus("FULL_RETURNED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("refund.approved 活动发药单缺位：DISPENSED 处方无活动单 warn 跳过（禁误照脏面）")
    void confirmRefundTerminalByRxSkipsWhenActiveDispenseMissing() {
        DispenseServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setStatus("DISPENSED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
        when(dispenseMapper.selectList(any())).thenReturn(List.of());

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("refund.approved 跨队列乱序：发药单受理未终态（如 ISSUED）warn 跳过待重投收敛")
    void confirmRefundTerminalByRxSkipsWhenDispenseNotTerminal() {
        DispenseServiceImpl impl = newService();
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setStatus("DISPENSED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
        when(dispenseMapper.selectList(any())).thenReturn(List.of(dispense("ISSUED")));

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("refund.approved 两级批查：处方+活动发药单各恰一次 IN 批查（2N 查收敛 2 查）+ 逐号 selectOne 零触达 "
            + "+ 二级键集=DISPENSED 命中集 + 混合状态输出等价")
    void confirmRefundTerminalByRxBatchesTwoLevelReadsOnceAndMirrorsEachDispensedRx() {
        DispenseServiceImpl impl = newService();
        // 清单五处方号混合面：两张 DISPENSED 待镜像（活动单 FULL/PART 各一）+ 一张 DISPENSED 无活动单
        //   + 一张非 DISPENSED（终态归作废通道）+ 一张脏差异缺号
        Prescription full = terminalRx(100L, "R20260918000001", "DISPENSED");
        Prescription part = terminalRx(101L, "R20260918000002", "DISPENSED");
        Prescription noActive = terminalRx(102L, "R20260918000003", "DISPENSED");
        Prescription undispensed = terminalRx(103L, "R20260918000004", "PENDING_DISPENSE");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(full, part, noActive, undispensed));
        Dispense fullReturned = dispense("FULL_RETURNED");
        Dispense partReturned = dispense("PART_RETURNED");
        partReturned.setRxNo("R20260918000002");
        when(dispenseMapper.selectList(any())).thenReturn(List.of(fullReturned, partReturned));
        when(prescriptionMapper.casStatus(100L, "DISPENSED", "FULL_RETURNED")).thenReturn(1);
        when(prescriptionMapper.casStatus(101L, "DISPENSED", "PART_RETURNED")).thenReturn(1);

        impl.confirmRefundTerminalByRx(
                List.of("R20260918000001", "R20260918000002", "R20260918000003", "R20260918000004", "R20260918999999"));

        // 两级批查各恰一次（旧逐号=5 号 7 查——5 次处方单查+2 次 DISPENSED 活动单单查；批查与行数
        //   解耦恒 2 查），逐号 selectOne 旧路径零触达
        verify(prescriptionMapper, times(1)).selectList(any());
        verify(dispenseMapper, times(1)).selectList(any());
        verify(prescriptionMapper, never()).selectOne(any());
        verify(dispenseMapper, never()).selectOne(any());
        // 一级键集契约=清单 rxNos 全集（含非 DISPENSED 与脏差异缺号——缺号不出结果集即原 null 分支）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Prescription>> rxQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(prescriptionMapper).selectList(rxQueryCaptor.capture());
        AbstractWrapper<?, ?, ?> rxWrapper = (AbstractWrapper<?, ?, ?>) rxQueryCaptor.getValue();
        rxWrapper.getSqlSegment(); // MP 条件参数在 getSqlSegment 惰性求值时才写入参数表
        assertThat(rxWrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(
                        "R20260918000001", "R20260918000002", "R20260918000003", "R20260918000004", "R20260918999999");
        // 二级键集契约=DISPENSED 命中集（无活动单号在集、结果集缺位即原 null 分支；非 DISPENSED 分支与
        //   脏差异缺号不进键集）+ 排 CANCELLED 谓词保持
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Dispense>> dispenseQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(dispenseMapper).selectList(dispenseQueryCaptor.capture());
        AbstractWrapper<?, ?, ?> dispenseWrapper = (AbstractWrapper<?, ?, ?>) dispenseQueryCaptor.getValue();
        dispenseWrapper.getSqlSegment();
        assertThat(dispenseWrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder("R20260918000001", "R20260918000002", "R20260918000003", "CANCELLED");
        // 混合状态输出等价：两张 DISPENSED 按清单序镜像（终态随各自活动单），无活动单/未发药/缺号三行零迁移
        InOrder mirrorOrder = inOrder(prescriptionMapper);
        mirrorOrder.verify(prescriptionMapper).casStatus(100L, "DISPENSED", "FULL_RETURNED");
        mirrorOrder.verify(prescriptionMapper).casStatus(101L, "DISPENSED", "PART_RETURNED");
        verify(prescriptionMapper, never()).casStatus(eq(102L), anyString(), anyString());
        verify(prescriptionMapper, never()).casStatus(eq(103L), anyString(), anyString());
    }

    @Test
    @DisplayName("未知受理模式：mode 字面量枚举外拒 PH-1013（400，零写面）")
    void acceptReturnRejectsUnknownModeAsBadRequest() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        DispenseReturnRequest badMode = new DispenseReturnRequest(
                "D20260918000001",
                "BAK_RETURN",
                List.of(new DispenseReturnRequest.ReturnLine("10", "1", List.of("TR-A1B2"))));

        assertThatThrownBy(() -> impl.acceptReturn(badMode))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(drugBatchMapper, never()).restock(anyLong(), any());
    }

    @Test
    @DisplayName("实物退缺追溯码：mode=ISSUED_RETURN 且行码为空拒 PH-1012（防回流核验不缺位）")
    void acceptReturnRejectsMissingTraceCodesAsPh1012() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("1", null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.TRACE_CODE_MISMATCH));
        verify(drugBatchMapper, never()).restock(anyLong(), any());
    }

    @Test
    @DisplayName("实物退状态守卫：发药单非 ISSUED/PART_RETURNED（如 PICKING）拒 PH-1013（时点①入口定性）")
    void acceptReturnRejectsNonReturnableStatus() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PICKING"));

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("1", List.of("TR-A1B2"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(dispenseItemMapper, never()).selectList(any());
        verify(drugBatchMapper, never()).restock(anyLong(), any());
    }

    @Test
    @DisplayName("退药受理缺行：请求行未覆盖发药明细拒 PH-1013（400，逐行对应缺位即拒、零写面）")
    void acceptReturnRejectsMissingReturnLine() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        // 请求行携 prescriptionItemId=99，与明细行 10 不对应（orElseThrow 缺行分支）
        DispenseReturnRequest missingLine = new DispenseReturnRequest(
                "D20260918000001",
                "ISSUED_RETURN",
                List.of(new DispenseReturnRequest.ReturnLine("99", "1", List.of("TR-A1B2"))));

        assertThatThrownBy(() -> impl.acceptReturn(missingLine))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(drugBatchMapper, never()).restock(anyLong(), any());
    }

    @Test
    @DisplayName("超可退数量守卫：已退 1+本次退 2 > 实发 2 拒 PH-1013（核心资金守卫——超退即实物账与退费双错）")
    void acceptReturnRejectsQuantityExceedingReturnable() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "1", "[\"TR-A1B2\",\"TR-C3D4\"]")));

        // EX-37 桩面通道迁移：零流水断言迁至 Db.saveBatch 批插通道（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("2", List.of("TR-A1B2", "TR-C3D4"))))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
            verify(drugBatchMapper, never()).restock(anyLong(), any());
            mockedDb.verify(() -> Db.saveBatch(any()), never());
        }
    }

    @Test
    @DisplayName("退药数量非数字串拒 PH-1016（400 显式拒，禁 NumberFormatException 直穿 500——零资金动作/零状态迁移）")
    void acceptReturnRejectsNonNumericReturnQuantityAsPh1016() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));

        // EX-37 桩面通道迁移：零流水断言迁至 Db.saveBatch 批插通道（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("两盒", List.of("TR-A1B2"))))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED));
            // 格式守卫在全部写面之前拦截：零回补/零流水/零回写/零终态迁移/零事件
            verify(drugBatchMapper, never()).restock(anyLong(), any());
            mockedDb.verify(() -> Db.saveBatch(any()), never());
            verify(prescriptionItemMapper, never()).accumulateReturnedQuantity(anyLong(), any());
            verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
            verify(events, never()).publishEvent(any());
        }
    }

    @Test
    @DisplayName("批次回补零行：restock 条件更新 0 行拒 PH-1013（批次状态漂移，整事务回滚）")
    void acceptReturnRejectsWhenRestockAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(0);

        // EX-37 桩面通道迁移：零流水断言迁至 Db.saveBatch 批插通道（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("1", List.of("TR-A1B2"))))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
            mockedDb.verify(() -> Db.saveBatch(any()), never());
            verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("退药终态并发被抢：casStatus(ISSUED→FULL_RETURNED) 0 行拒 PH-1009（returned 事件后置锚、违例禁出）")
    void acceptReturnRejectsWhenTerminalCasAffectsZeroRowsAsPh1009() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("2"))).thenReturn(1);
        // 退药累计回写打桩命中（本次改动新增写面，置于终态 CAS 之前）——本用例靶点为终态并发被抢分支
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("2")))
                .thenReturn(1);
        when(dispenseMapper.casStatus(900L, "ISSUED", "FULL_RETURNED")).thenReturn(0);

        // EX-37 桩面补位：流水/退药批写在终态 CAS 前已发生（真实库随 CAS 违例整事务回滚），
        //   静态 Db 桩内执行；insert/updateById 旧通道桩移除
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("2", List.of("TR-A1B2", "TR-C3D4"))))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
            verify(events, never()).publishEvent(any()); // 事件发布后置于终态迁移，并发被抢即整事务回滚
        }
    }

    @Test
    @DisplayName("发药中退场零行：releaseLock 条件更新 0 行拒 PH-1013（锁定数不符整事务回滚）")
    void dispensingCancelRejectsWhenReleaseLockAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        Dispense picking = dispense("PICKING");
        when(dispenseMapper.selectOne(any())).thenReturn(picking);
        DispenseItem lockRow = issuedItem("2", "0", "[]");
        lockRow.setIssuedQty(BigDecimal.ZERO);
        lockRow.setBatchId(55L);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(lockRow));
        when(drugBatchMapper.releaseLock(55L, new BigDecimal("2"))).thenReturn(0);

        assertThatThrownBy(() -> impl.acceptReturn(new DispenseReturnRequest(
                        "D20260918000001",
                        "DISPENSING_CANCEL",
                        List.of(new DispenseReturnRequest.ReturnLine("10", "2", null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("发药中退场状态守卫：发药单非 PICKING（如 CREATED）拒 PH-1013（时点②入口定性）")
    void dispensingCancelRejectsNonPickingDispense() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("CREATED"));

        assertThatThrownBy(() -> impl.acceptReturn(new DispenseReturnRequest(
                        "D20260918000001",
                        "DISPENSING_CANCEL",
                        List.of(new DispenseReturnRequest.ReturnLine("10", "2", null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(dispenseItemMapper, never()).selectList(any());
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
    }

    @Test
    @DisplayName("明细退场缺行：请求行未覆盖配药明细拒 PH-1013（400，锁定释放零触碰）")
    void dispensingCancelRejectsMissingReturnLine() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("PICKING"));
        DispenseItem picking = issuedItem("2", "0", "[]");
        picking.setIssuedQty(BigDecimal.ZERO);
        picking.setBatchId(55L);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(picking));

        assertThatThrownBy(() -> impl.acceptReturn(new DispenseReturnRequest(
                        "D20260918000001",
                        "DISPENSING_CANCEL",
                        List.of(new DispenseReturnRequest.ReturnLine("99", "2", null))))) // 与明细行 10 不对应
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
    }

    @Test
    @DisplayName("终态镜像缺行：refund.approved 清单处方号定位不到处方 warn 留痕继续（禁阻断消费位）")
    void confirmRefundTerminalByRxSkipsWhenPrescriptionMissing() {
        DispenseServiceImpl impl = newService();
        when(prescriptionMapper.selectList(any())).thenReturn(List.of());

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("未发药退费 warn 跳过：处方非 DISPENSED/已终态（如 PENDING_DISPENSE）不镜像（终态归 order.cancelled 实装通道）")
    void confirmRefundTerminalByRxSkipsNonDispensedPrescription() {
        DispenseServiceImpl impl = newService();
        Prescription pending = new Prescription();
        pending.setId(100L);
        pending.setRxNo("R20260918000001");
        pending.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(pending));

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }
}
