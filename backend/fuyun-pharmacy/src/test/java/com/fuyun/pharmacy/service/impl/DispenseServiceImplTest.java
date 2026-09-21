package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.SettlementQueryPort;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import com.fuyun.pharmacy.entity.StockLedger;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.mapper.StockLedgerMapper;
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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 发药服务单测（charged 单据精确放行 / fee.created 迁移 / verify 凭证核验 / order.cancelled
 * 未发药作废四入口）：状态 CAS + 幂等重读定性 + 发药单明细入队；PR-5 Task 11 回切——
 * 放行按 rxNos 精确清单（裁决 4）、verify 凭证=settlementNo 归属核验（裁决 8）、
 * 作废路径实装（注记⑥回切）。调剂三段用例归 DispenseThreeStepTest，退药用例归 DispenseReturnTest。
 */
@ExtendWith(MockitoExtension.class)
class DispenseServiceImplTest {

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

    /** Task 6 起构造器扩九参：调剂三段依赖补位（本类入口不触达，仅满足构造） */
    @Mock
    private IBatchSelectService batchSelectService;

    @Mock
    private ApplicationEventPublisher events;

    /** Task 10 起构造器扩十参：主数据读侧缓存补位（占用查询读侧归一，本类入口不触达） */
    @Mock
    private com.fuyun.pharmacy.cache.PharmacyMasterDataCache masterDataCache;

    /** Task 11 起构造器扩十一参：结算单反查端口补位（verify 凭证核验唯一消费方，billing api 契约） */
    @Mock
    private SettlementQueryPort settlementQueryPort;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispenseItem.class);
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体均须手工注册表信息（放行/入队/作废三读面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Prescription.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrescriptionItem.class);
    }

    @BeforeEach
    void loginOperator() {
        OperatorContextHolder.set("verifier-01");
    }

    @AfterEach
    void clearOperator() {
        OperatorContextHolder.clear();
    }

    private DispenseServiceImpl newService() {
        // 构造器十一参直注（Task 11 扩第十一参 settlementQueryPort；objectMapper 用真实例，与本域单测同款）
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
                masterDataCache,
                settlementQueryPort);
        ReflectionTestUtils.setField(impl, "baseMapper", dispenseMapper);
        return impl;
    }

    private Prescription rxPendingFee(long id, String rxNo) {
        Prescription rx = new Prescription();
        rx.setId(id);
        rx.setRxNo(rxNo);
        rx.setPatientId(700101L);
        rx.setVisitId("O2026091800001");
        rx.setRxType("OUTPATIENT");
        rx.setStatus("PENDING_FEE");
        return rx;
    }

    private PrescriptionItem rxItem(long id, long rxId) {
        PrescriptionItem item = new PrescriptionItem();
        item.setId(id);
        item.setPrescriptionId(rxId);
        item.setDrugId(11L);
        item.setItemCode("C0131230900157");
        item.setQuantity(new BigDecimal("2"));
        return item;
    }

    private Dispense activeDispense(String status) {
        Dispense d = new Dispense();
        d.setId(900L);
        d.setDispenseNo("D20260918000001");
        d.setRxNo("R20260918000001");
        d.setPatientId(700101L);
        d.setVisitId("O2026091800001");
        d.setStorehouse("OUTP_PHARM");
        d.setStatus(status);
        d.setPicker("picker-01");
        return d;
    }

    @Test
    @DisplayName("charged 单据精确放行：同 visit 两 PENDING 处方仅清单内目标单 CAS+建发药单（裁决 4 核心）")
    void releaseByRxNosReleasesExactlyListedPrescriptions() {
        DispenseServiceImpl impl = newService();
        Prescription listed = rxPendingFee(100L, "R20260918000001");
        Prescription unlisted = rxPendingFee(101L, "R20260918000002");
        // 同 visit 两处方在库，本次结算清单仅覆盖其一——未列入方禁被放行（患者维度误伤面回归锚）
        when(prescriptionMapper.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment(); // MP 条件参数在 getSqlSegment 惰性求值时才写入参数表
            return wrapper.getParamNameValuePairs().containsValue("R20260918000001") ? listed : unlisted;
        });
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(1);
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of(rxItem(1L, 100L)));
        when(dispenseMapper.insert(any(Dispense.class))).thenAnswer(inv -> {
            inv.getArgument(0, Dispense.class).setId(900L);
            return 1;
        });

        // A.4.3-16：明细一次批插（JDBC 批处理 + ASSIGN_ID 自动填充），逐条 insert 通道已下线
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.releaseByRxNos(List.of("R20260918000001"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> rowsCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.saveBatch(rowsCaptor.capture()));
            assertThat(rowsCaptor.getValue()).hasSize(1);
            assertThat(rowsCaptor.getValue().get(0).getRequestedQty()).isEqualByComparingTo("2");
        }

        // 单据精确核心断言：仅清单内处方被定位与放行，未列入处方零 CAS 零建单
        verify(prescriptionMapper, times(1)).selectOne(any());
        verify(prescriptionMapper).casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE");
        verify(prescriptionMapper, never()).casStatus(eq(101L), any(), any());
        ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).insert(dispenseCaptor.capture());
        Dispense created = dispenseCaptor.getValue();
        assertThat(created.getStatus()).isEqualTo("CREATED");
        assertThat(created.getRxNo()).isEqualTo("R20260918000001");
        assertThat(created.getDispenseType()).isEqualTo("OUTPATIENT");
        assertThat(created.getStorehouse()).isEqualTo("OUTP_PHARM");
    }

    @Test
    @DisplayName("charged 空清单跳过：该结算无药品行（纯检查/检验结算）零查询零写面")
    void releaseByRxNosSkipsEmptyList() {
        DispenseServiceImpl impl = newService();

        impl.releaseByRxNos(List.of());

        verifyNoInteractions(prescriptionMapper, dispenseMapper, dispenseItemMapper, prescriptionItemMapper);
    }

    @Test
    @DisplayName("charged 重复投递幂等：处方已放行（CAS 0 行重读已达标）不再建单")
    void releaseByRxNosIsIdempotentWhenAlreadyReleased() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(0);
        when(prescriptionMapper.selectById(100L)).thenReturn(rx);

        impl.releaseByRxNos(List.of("R20260918000001"));

        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("charged 状态漂移跳过：CAS 0 行且重读非放行态（如 CANCELLED）零建单（warn 分支覆盖）")
    void releaseByRxNosSkipsDriftedPrescriptionWithoutQueueing() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(0);
        Prescription drifted = rxPendingFee(100L, "R20260918000001");
        drifted.setStatus("CANCELLED");
        when(prescriptionMapper.selectById(100L)).thenReturn(drifted);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.releaseByRxNos(List.of("R20260918000001"));

            verify(dispenseMapper, never()).insert(any(Dispense.class));
            mockedDb.verify(() -> Db.saveBatch(any()), never()); // 明细零批插（warn 分支零写面）
        }
    }

    @Test
    @DisplayName("charged 脏差异守卫：清单处方号无法定位处方 warn 留痕不阻断同批放行")
    void releaseByRxNosSkipsUnknownRxNo() {
        DispenseServiceImpl impl = newService();
        when(prescriptionMapper.selectOne(any())).thenReturn(null);

        impl.releaseByRxNos(List.of("R20260918999999"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("charged 通道过滤：非门诊/急诊处方（如 DISCHARGE 出院带药）不放行（归 settlement.completed 分支）")
    void releaseByRxNosSkipsNonOutpatientRxType() {
        DispenseServiceImpl impl = newService();
        Prescription discharge = rxPendingFee(100L, "R20260918000001");
        discharge.setRxType("DISCHARGE");
        when(prescriptionMapper.selectOne(any())).thenReturn(discharge);

        impl.releaseByRxNos(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("fee.created 迁移：处方通道 billingKey 解析 rxNo 后 APPROVED→PENDING_FEE")
    void markPendingFeeTransitionsApprovedPrescription() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("APPROVED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "APPROVED", "PENDING_FEE")).thenReturn(1);

        impl.markPendingFee("700101|R20260918000001|PRESCRIPTION_EFFECTIVE|55|2026-09-18");

        verify(prescriptionMapper).casStatus(100L, "APPROVED", "PENDING_FEE");
    }

    @Test
    @DisplayName("fee.created 非处方通道：触发型不符静默跳过（零交互）")
    void markPendingFeeSkipsNonPrescriptionTrigger() {
        DispenseServiceImpl impl = newService();

        impl.markPendingFee("700101|ORD-1|ORDER_CONFIRMED|55|2026-09-18");

        verify(prescriptionMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("fee.created 脏数据守卫：billingKey 处方号无法定位处方 warn 留痕不阻断（null 分支）")
    void markPendingFeeSkipsWhenRxNoUnknown() {
        DispenseServiceImpl impl = newService();
        when(prescriptionMapper.selectOne(any())).thenReturn(null);

        impl.markPendingFee("700101|R20260918999999|PRESCRIPTION_EFFECTIVE|55|2026-09-18");

        verify(prescriptionMapper, never()).casStatus(100L, "APPROVED", "PENDING_FEE");
    }

    @Test
    @DisplayName("fee.created 幂等：CAS 0 行重读已 PENDING_FEE（回执重投）零异常零重复迁移")
    void markPendingFeeIsIdempotentWhenAlreadyPendingFee() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("PENDING_FEE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "APPROVED", "PENDING_FEE")).thenReturn(0);
        when(prescriptionMapper.selectById(100L)).thenReturn(rx);

        impl.markPendingFee("700101|R20260918000001|PRESCRIPTION_EFFECTIVE|55|2026-09-18");

        verify(prescriptionMapper).casStatus(100L, "APPROVED", "PENDING_FEE");
    }

    @Test
    @DisplayName("fee.created 状态漂移跳过：CAS 0 行且重读非 PENDING_FEE（如已作废）零迁移（warn 分支覆盖）")
    void markPendingFeeSkipsWhenStatusDrifted() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "APPROVED", "PENDING_FEE")).thenReturn(0);
        Prescription drifted = rxPendingFee(100L, "R20260918000001");
        drifted.setStatus("CANCELLED");
        when(prescriptionMapper.selectById(100L)).thenReturn(drifted);

        impl.markPendingFee("700101|R20260918000001|PRESCRIPTION_EFFECTIVE|55|2026-09-18");

        verify(prescriptionMapper).casStatus(100L, "APPROVED", "PENDING_FEE");
    }

    @Test
    @DisplayName("verify 凭证核验通过：settlementNo 与处方归属一致（反查 true）→ PICKED+核对留痕回填")
    void verifyWithMatchingCredentialPasses() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(activeDispense("PICKING"));
        when(settlementQueryPort.settledUnder("S20260918000001", "R20260918000001"))
                .thenReturn(true);
        when(dispenseMapper.casStatus(900L, "PICKING", "PICKED")).thenReturn(1);

        impl.verify("D20260918000001", "S20260918000001");

        verify(settlementQueryPort).settledUnder("S20260918000001", "R20260918000001");
        ArgumentCaptor<Dispense> captor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PICKED");
        assertThat(captor.getValue().getVerifier()).isEqualTo("verifier-01");
    }

    @Test
    @DisplayName("verify 凭证不符拒：settlementNo 与处方归属不一致反查 false → PH-1018（409，零状态迁移）")
    void verifyWithMismatchedCredentialRejected() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(activeDispense("PICKING"));
        when(settlementQueryPort.settledUnder("S20260918000002", "R20260918000001"))
                .thenReturn(false);

        assertThatThrownBy(() -> impl.verify("D20260918000001", "S20260918000002"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.CREDENTIAL_MISMATCH));
        // 守卫前置即拒：零 CAS 零留痕（单据停留 PICKING）
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).updateById(any(Dispense.class));
    }

    @Test
    @DisplayName("verify 无凭证跳过核验：credential 为 null 零反查（追溯码防回流主道不变，注记⑧豁免面闭合）")
    void verifyWithoutCredentialSkipsCheck() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(activeDispense("PICKING"));
        when(dispenseMapper.casStatus(900L, "PICKING", "PICKED")).thenReturn(1);

        impl.verify("D20260918000001", null);

        verifyNoInteractions(settlementQueryPort);
        ArgumentCaptor<Dispense> captor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PICKED");
        assertThat(captor.getValue().getVerifier()).isEqualTo("verifier-01");
    }

    @Test
    @DisplayName("order.cancelled 未发药作废：PENDING_DISPENSE 处方 CAS 终态+发药单同步 CANCELLED（注记⑥回切）")
    void orderCancelledVoidsUndispensedPrescription() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        // 放行入队 CREATED 发药单：明细未锁批（batchId 缺位），退场零释放面
        Dispense created = activeDispense("CREATED");
        when(dispenseMapper.selectList(any())).thenReturn(List.of(created));
        DispenseItem plain = new DispenseItem();
        plain.setId(1L);
        plain.setDispenseId(900L);
        plain.setRequestedQty(new BigDecimal("2"));
        plain.setItemStatus("NORMAL");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(plain));
        when(dispenseMapper.casStatus(900L, "CREATED", "CANCELLED")).thenReturn(1);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        verify(prescriptionMapper).casStatus(100L, "PENDING_DISPENSE", "CANCELLED");
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any()); // 未锁批零释放
        ArgumentCaptor<DispenseItem> itemCaptor = ArgumentCaptor.forClass(DispenseItem.class);
        verify(dispenseItemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getItemStatus()).isEqualTo("CANCELLED");
        verify(dispenseMapper).casStatus(900L, "CREATED", "CANCELLED");
    }

    @Test
    @DisplayName("order.cancelled 配药中作废：DISPENSING 态释放批次锁定+明细退场+发药单 CANCELLED")
    void orderCancelledReleasesBatchLocksForDispensing() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSING");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "CANCELLED")).thenReturn(1);
        Dispense picking = activeDispense("PICKING");
        when(dispenseMapper.selectList(any())).thenReturn(List.of(picking));
        DispenseItem locked = new DispenseItem();
        locked.setId(1L);
        locked.setDispenseId(900L);
        locked.setBatchId(55L);
        locked.setRequestedQty(new BigDecimal("2"));
        locked.setItemStatus("NORMAL");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(locked));
        when(drugBatchMapper.releaseLock(55L, new BigDecimal("2"))).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "PICKING", "CANCELLED")).thenReturn(1);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        // 锁定数非数量流水：释放不落 stock_ledger（DISPENSING_CANCEL 退场段同款形态）
        verify(drugBatchMapper).releaseLock(55L, new BigDecimal("2"));
        verify(stockLedgerMapper, never()).insert(any(StockLedger.class));
        ArgumentCaptor<DispenseItem> itemCaptor = ArgumentCaptor.forClass(DispenseItem.class);
        verify(dispenseItemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getItemStatus()).isEqualTo("CANCELLED");
        verify(dispenseMapper).casStatus(900L, "PICKING", "CANCELLED");
    }

    @Test
    @DisplayName("order.cancelled 已发药幂等：DISPENSED 态零写 info 跳过（refund.approved 双通道已收敛）")
    void orderCancelledIdempotentForDispensedRx() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).selectList(any());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
        verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
    }

    @Test
    @DisplayName("order.cancelled 脏差异守卫：清单处方号无法定位处方 warn 留痕不阻断同批作废")
    void orderCancelledSkipsUnknownRxNo() {
        DispenseServiceImpl impl = newService();
        when(prescriptionMapper.selectOne(any())).thenReturn(null);

        impl.voidUndispensedByRx(List.of("R20260918999999"), "退费逆向终态确认");

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("order.cancelled 幂等重投：处方已 CANCELLED 零写 info 跳过")
    void orderCancelledIsIdempotentWhenAlreadyCancelled() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("CANCELLED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("order.cancelled 状态域外：未缴费处方（如 APPROVED）不归退费逆向作废通道（warn 留痕）")
    void orderCancelledSkipsPrescriptionOutsideVoidDomain() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("APPROVED");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("order.cancelled 作废并发被抢：CAS 0 行重读已终态（如 DISPENSED）info 跳过（与发药签名竞态收敛）")
    void orderCancelledIsIdempotentWhenRaceLostToDispensing() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSING");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "CANCELLED")).thenReturn(0);
        Prescription dispensed = rxPendingFee(100L, "R20260918000001");
        dispensed.setStatus("DISPENSED");
        when(prescriptionMapper.selectById(100L)).thenReturn(dispensed);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        // 处方 CAS 先行：0 行即整单跳过，发药单/锁零触碰（禁半程写面）
        verify(dispenseMapper, never()).selectList(any());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
    }

    @Test
    @DisplayName("order.cancelled 作废并发被抢：CAS 0 行重读仍未发药域外态（warn 漂移留痕零写面）")
    void orderCancelledSkipsWhenRaceLostToUnknownDrift() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSING");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "CANCELLED")).thenReturn(0);
        Prescription drifted = rxPendingFee(100L, "R20260918000001");
        drifted.setStatus("PENDING_FEE");
        when(prescriptionMapper.selectById(100L)).thenReturn(drifted);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
    }

    @Test
    @DisplayName("order.cancelled 脏数据显式暴露：未发药处方挂已发药活动单（ISSUED）拒 PH-1009 整事务回滚")
    void orderCancelledRejectsIssuedActiveDispenseAsDirtyData() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        when(dispenseMapper.selectList(any())).thenReturn(List.of(activeDispense("ISSUED")));

        assertThatThrownBy(() -> impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
        verify(dispenseMapper, never()).casStatus(eq(900L), any(), any());
    }

    @Test
    @DisplayName("order.cancelled 锁定释放零行：releaseLock 条件更新 0 行拒 PH-1013（批次漂移整事务回滚）")
    void orderCancelledRejectsWhenReleaseLockAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSING");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "CANCELLED")).thenReturn(1);
        when(dispenseMapper.selectList(any())).thenReturn(List.of(activeDispense("PICKING")));
        DispenseItem locked = new DispenseItem();
        locked.setId(1L);
        locked.setDispenseId(900L);
        locked.setBatchId(55L);
        locked.setRequestedQty(new BigDecimal("2"));
        locked.setItemStatus("NORMAL");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(locked));
        when(drugBatchMapper.releaseLock(55L, new BigDecimal("2"))).thenReturn(0);

        assertThatThrownBy(() -> impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(dispenseMapper, never()).casStatus(eq(900L), any(), any());
    }

    @Test
    @DisplayName("order.cancelled 发药单作废并发被抢：CAS 0 行拒 PH-1009（事务回滚重投再定性）")
    void orderCancelledRejectsWhenDispenseCasAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        when(dispenseMapper.selectList(any())).thenReturn(List.of(activeDispense("CREATED")));
        DispenseItem plain = new DispenseItem();
        plain.setId(1L);
        plain.setDispenseId(900L);
        plain.setRequestedQty(new BigDecimal("2"));
        plain.setItemStatus("NORMAL");
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(plain));
        when(dispenseMapper.casStatus(900L, "CREATED", "CANCELLED")).thenReturn(0);

        assertThatThrownBy(() -> impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
    }
}
