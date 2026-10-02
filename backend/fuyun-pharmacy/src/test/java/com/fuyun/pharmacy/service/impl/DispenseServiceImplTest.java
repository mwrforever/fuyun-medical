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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.SettlementQueryPort;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.pharmacy.api.PharmacyErrorCode;
import com.fuyun.pharmacy.dto.DispenseReturnRequest;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
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

    /** P2 PR-3 Task 8 起构造器扩十二参：住院摆药计划服务（acceptReturn 住院形态分流委托承载） */
    @Mock
    private com.fuyun.pharmacy.service.IDispensePlanService dispensePlanService;

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
        // 构造器十参直注（Task 11 扩第十一参 settlementQueryPort、EX-37 收敛十参——流水批插改 Db
        // 通道后 StockLedgerMapper 依赖卸除；objectMapper 用真实例，与本域单测同款）
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

    /** 作废链 NORMAL 明细行构造（batchId 传 null 表 CREATED 单未锁批——零释放面分支锚点） */
    private DispenseItem normalItem(long id, long dispenseId, Long batchId) {
        DispenseItem item = new DispenseItem();
        item.setId(id);
        item.setDispenseId(dispenseId);
        item.setBatchId(batchId);
        item.setRequestedQty(new BigDecimal("2"));
        item.setItemStatus("NORMAL");
        return item;
    }

    @Test
    @DisplayName("charged 单据精确放行：同 visit 两 PENDING 处方仅清单内目标单 CAS+建发药单（裁决 4 核心）")
    void releaseByRxNosReleasesExactlyListedPrescriptions() {
        DispenseServiceImpl impl = newService();
        Prescription listed = rxPendingFee(100L, "R20260918000001");
        Prescription unlisted = rxPendingFee(101L, "R20260918000002");
        // 同 visit 两处方在库，本次结算清单仅覆盖其一——未列入方不入批查键集即不出结果集
        // （患者维度误伤面回归锚）
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(listed));
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

        // 单据精确核心断言：处方批查恰一次（键集=清单），未列入处方零 CAS 零建单
        verify(prescriptionMapper, times(1)).selectList(any());
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of());

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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(discharge));

        impl.releaseByRxNos(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("charged 放行批查：多 rxNo 清单键集一次 IN 批查（N 号 N 查收敛 1 查）+ 逐号 selectOne 零触达 " + "+ 脏差异缺号 warn 不阻断同批 + 逐号放行输出等价")
    void releaseByRxNosBatchesPrescriptionReadOnceAndReleasesEachListedRx() {
        DispenseServiceImpl impl = newService();
        // 清单三处方号：两张待放行（PENDING_FEE）+ 一张脏差异缺号（批查结果集缺位→原 null 分支）
        Prescription first = rxPendingFee(100L, "R20260918000001");
        Prescription second = rxPendingFee(101L, "R20260918000002");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(first, second));
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(1);
        when(prescriptionMapper.casStatus(101L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(1);
        // createDispense 逐号取处方明细（写侧原形态零触碰）：按清单处理序遇序两次各回各行
        when(prescriptionItemMapper.selectList(any()))
                .thenReturn(List.of(rxItem(1L, 100L)))
                .thenReturn(List.of(rxItem(2L, 101L)));
        when(dispenseMapper.insert(any(Dispense.class))).thenAnswer(inv -> {
            inv.getArgument(0, Dispense.class).setId(900L);
            return 1;
        });

        // A.4.3-16：明细一次批插通道保持（写侧零触碰），静态 Db 桩内执行放行
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.releaseByRxNos(List.of("R20260918000001", "R20260918999999", "R20260918000002"));

            // 放行处方批查恰一次（旧逐号=3 号 3 查；批查与行数解耦恒 1 查），逐号 selectOne 旧路径零触达
            verify(prescriptionMapper, times(1)).selectList(any());
            verify(prescriptionMapper, never()).selectOne(any());
            // 批查键集契约=清单 rxNos 全集（含脏差异缺号——缺号不出结果集即原 null 分支）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<Prescription>> rxQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(prescriptionMapper).selectList(rxQueryCaptor.capture());
            AbstractWrapper<?, ?, ?> rxWrapper = (AbstractWrapper<?, ?, ?>) rxQueryCaptor.getValue();
            rxWrapper.getSqlSegment(); // MP 条件参数在 getSqlSegment 惰性求值时才写入参数表
            assertThat(rxWrapper.getParamNameValuePairs().values())
                    .containsExactlyInAnyOrder("R20260918000001", "R20260918999999", "R20260918000002");
            // 脏差异缺号 warn 不阻断同批放行：两待放行号仍按清单序 CAS（遇序保序）与建单入队
            InOrder releaseOrder = inOrder(prescriptionMapper);
            releaseOrder.verify(prescriptionMapper).casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE");
            releaseOrder.verify(prescriptionMapper).casStatus(101L, "PENDING_FEE", "PENDING_DISPENSE");
            ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
            verify(dispenseMapper, times(2)).insert(dispenseCaptor.capture());
            assertThat(dispenseCaptor.getAllValues())
                    .extracting(Dispense::getRxNo)
                    .containsExactly("R20260918000001", "R20260918000002");
            assertThat(dispenseCaptor.getAllValues())
                    .allSatisfy(d -> assertThat(d.getStatus()).isEqualTo("CREATED"));
            mockedDb.verify(() -> Db.saveBatch(any()), times(2));
        }
    }

    @Test
    @DisplayName("charged 放行批查重复键防御：同 rxNo 重复行保留首行（CAS 与建单快照取首行 id/患者）")
    void releaseByRxNosKeepsFirstPrescriptionRowOnDuplicateRxNo() {
        DispenseServiceImpl impl = newService();
        // 处方批查结果集同 rxNo 两行（uk_rx_no 脏数据防御面）：首行 PENDING_FEE 门诊（id=100/
        //   患者=700101）、次行同号不同 id/患者——toMap 重复键保留首行，后续判定以首行为准
        Prescription first = rxPendingFee(100L, "R20260918000001");
        Prescription duplicate = rxPendingFee(201L, "R20260918000001");
        duplicate.setPatientId(700999L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(first, duplicate));
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(1);
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of());
        when(dispenseMapper.insert(any(Dispense.class))).thenAnswer(inv -> {
            inv.getArgument(0, Dispense.class).setId(900L);
            return 1;
        });

        // A.4.3-16：明细批插通道承载（空行集批插，静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.releaseByRxNos(List.of("R20260918000001"));
            mockedDb.verify(() -> Db.saveBatch(any()));
        }

        // 首行语义断言：放行 CAS 取首行 id=100（重复行 id=201 零触达），建单处方/患者快照取首行字段值
        verify(prescriptionMapper).casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE");
        verify(prescriptionMapper, never()).casStatus(eq(201L), anyString(), anyString());
        ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).insert(dispenseCaptor.capture());
        assertThat(dispenseCaptor.getValue().getPrescriptionId()).isEqualTo(100L);
        assertThat(dispenseCaptor.getValue().getPatientId()).isEqualTo(700101L);
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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

        // EX-37 桩面通道迁移：明细退场从逐行 updateById 改 Db.updateBatchById 批更（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

            verify(prescriptionMapper).casStatus(100L, "PENDING_DISPENSE", "CANCELLED");
            verify(drugBatchMapper, never()).releaseLock(anyLong(), any()); // 未锁批零释放
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(itemCaptor.capture()));
            assertThat(itemCaptor.getValue().get(0).getItemStatus()).isEqualTo("CANCELLED");
            verify(dispenseMapper).casStatus(900L, "CREATED", "CANCELLED");
        }
    }

    @Test
    @DisplayName("order.cancelled 配药中作废：DISPENSING 态释放批次锁定+明细退场+发药单 CANCELLED")
    void orderCancelledReleasesBatchLocksForDispensing() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSING");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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

        // EX-37 桩面通道迁移：明细退场从逐行 updateById 改 Db.updateBatchById 批更（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

            // 锁定数非数量流水：释放不落 stock_ledger（DISPENSING_CANCEL 退场段同款形态；
            //   EX-37 桩面通道迁移——零流水断言迁至 Db.saveBatch 批插通道）
            verify(drugBatchMapper).releaseLock(55L, new BigDecimal("2"));
            mockedDb.verify(() -> Db.saveBatch(any()), never());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(itemCaptor.capture()));
            assertThat(itemCaptor.getValue().get(0).getItemStatus()).isEqualTo("CANCELLED");
            verify(dispenseMapper).casStatus(900L, "PICKING", "CANCELLED");
        }
    }

    @Test
    @DisplayName("order.cancelled 已发药幂等：DISPENSED 态零写 info 跳过（refund.approved 双通道已收敛）")
    void orderCancelledIdempotentForDispensedRx() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
        // OPT-07 批查契约：命中处方（含终态跳过行）参与活动发药单键集批查恰一次；业务断言=零写面不变
        verify(dispenseMapper, times(1)).selectList(any());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
        verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
    }

    @Test
    @DisplayName("order.cancelled 脏差异守卫：清单处方号无法定位处方 warn 留痕不阻断同批作废")
    void orderCancelledSkipsUnknownRxNo() {
        DispenseServiceImpl impl = newService();
        when(prescriptionMapper.selectList(any())).thenReturn(List.of());

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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));

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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));

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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
        when(prescriptionMapper.casStatus(100L, "DISPENSING", "CANCELLED")).thenReturn(0);
        Prescription dispensed = rxPendingFee(100L, "R20260918000001");
        dispensed.setStatus("DISPENSED");
        when(prescriptionMapper.selectById(100L)).thenReturn(dispensed);

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        // 处方 CAS 先行：0 行即整单跳过，发药单/锁零写面（禁半程写面）；
        // OPT-07 批查契约：命中处方参与活动发药单键集批查恰一次（读面与守卫判定解耦）
        verify(dispenseMapper, times(1)).selectList(any());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
    }

    @Test
    @DisplayName("order.cancelled 作废并发被抢：CAS 0 行重读仍未发药域外态（warn 漂移留痕零写面）")
    void orderCancelledSkipsWhenRaceLostToUnknownDrift() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L, "R20260918000001");
        rx.setStatus("DISPENSING");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
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

        // EX-37 桩面补位：单内明细退场批写先于单据 CAS 发生（真实库随 CAS 违例整事务回滚，静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(() -> impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认"))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
        }
    }

    @Test
    @DisplayName("order.cancelled 三级级联批查：多处方多单多明细各恰一次（N+N×M+N×M×K→恒3查）+ 逐行作废行为等价")
    void orderCancelledBatchesThreeCascadeReadsOnceAndVoidsEachListedRx() {
        DispenseServiceImpl impl = newService();
        // 清单四处方号：两张待作废（PENDING_DISPENSE/DISPENSING）+ 一张已发药终态（守卫跳过行）
        // + 一张脏差异缺号（批查结果集缺位）——守卫在批查后按原循环序逐行判定的完整混合面
        Prescription voiding1 = rxPendingFee(100L, "R20260918000001");
        voiding1.setStatus("PENDING_DISPENSE");
        Prescription voiding2 = rxPendingFee(101L, "R20260918000002");
        voiding2.setStatus("DISPENSING");
        Prescription dispensed = rxPendingFee(102L, "R20260918000003");
        dispensed.setStatus("DISPENSED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(voiding1, voiding2, dispensed));
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        when(prescriptionMapper.casStatus(101L, "DISPENSING", "CANCELLED")).thenReturn(1);
        Dispense created = activeDispense("CREATED");
        Dispense picking = activeDispense("PICKING");
        picking.setId(901L);
        picking.setDispenseNo("D20260918000002");
        picking.setRxNo("R20260918000002");
        when(dispenseMapper.selectList(any())).thenReturn(List.of(created, picking));
        DispenseItem plain = normalItem(1L, 900L, null);
        DispenseItem lockedOfCreated = normalItem(2L, 900L, 55L);
        DispenseItem lockedOfPicking = normalItem(3L, 901L, 66L);
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(plain, lockedOfCreated, lockedOfPicking));
        when(drugBatchMapper.releaseLock(eq(55L), any())).thenReturn(1);
        when(drugBatchMapper.releaseLock(eq(66L), any())).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "CREATED", "CANCELLED")).thenReturn(1);
        when(dispenseMapper.casStatus(901L, "PICKING", "CANCELLED")).thenReturn(1);

        // EX-37 桩面通道迁移：明细退场从逐行 updateById 改逐单 Db.updateBatchById 批更（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.voidUndispensedByRx(
                    List.of("R20260918000001", "R20260918000002", "R20260918000003", "R20260918000004"), "退费逆向终态确认");

            // 三级批查各恰一次（旧逐行链=4 号 selectOne+守卫通过方逐处方/逐单 selectList；批查契约下
            //   与行数解耦恒 3 查），处方逐号 selectOne 旧路径零触达
            verify(prescriptionMapper, times(1)).selectList(any());
            verify(prescriptionMapper, never()).selectOne(any());
            verify(dispenseMapper, times(1)).selectList(any());
            verify(dispenseItemMapper, times(1)).selectList(any());
            // 批查键集契约：处方批查键集=清单 rxNos 全集；活动单批查键集=全部命中处方 rxNo 集
            //   （脏差异缺号不入键集，只排除 CANCELLED 一个谓词值）；明细批查键集=全部涉及发药单 id 集
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<Prescription>> rxQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(prescriptionMapper).selectList(rxQueryCaptor.capture());
            AbstractWrapper<?, ?, ?> rxWrapper = (AbstractWrapper<?, ?, ?>) rxQueryCaptor.getValue();
            rxWrapper.getSqlSegment(); // MP 条件参数在 getSqlSegment 惰性求值时才写入参数表
            assertThat(rxWrapper.getParamNameValuePairs().values())
                    .containsExactlyInAnyOrder(
                            "R20260918000001", "R20260918000002", "R20260918000003", "R20260918000004");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<Dispense>> dispenseQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(dispenseMapper).selectList(dispenseQueryCaptor.capture());
            AbstractWrapper<?, ?, ?> dispenseWrapper = (AbstractWrapper<?, ?, ?>) dispenseQueryCaptor.getValue();
            dispenseWrapper.getSqlSegment();
            assertThat(dispenseWrapper.getParamNameValuePairs().values())
                    .containsExactlyInAnyOrder("R20260918000001", "R20260918000002", "R20260918000003", "CANCELLED");
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<DispenseItem>> itemQueryCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(dispenseItemMapper).selectList(itemQueryCaptor.capture());
            AbstractWrapper<?, ?, ?> itemWrapper = (AbstractWrapper<?, ?, ?>) itemQueryCaptor.getValue();
            itemWrapper.getSqlSegment();
            assertThat(itemWrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrder(900L, 901L, "NORMAL");
            // 逐行判定输出与旧实现行为快照等价：守卫行与缺号零写，作废行按清单序逐行 CAS（遇序保序）
            verify(prescriptionMapper, never()).casStatus(eq(102L), anyString(), anyString());
            InOrder voidOrder = inOrder(prescriptionMapper);
            voidOrder.verify(prescriptionMapper).casStatus(100L, "PENDING_DISPENSE", "CANCELLED");
            voidOrder.verify(prescriptionMapper).casStatus(101L, "DISPENSING", "CANCELLED");
            // 明细全部退场为 CANCELLED（三行全改，含 CREATED 单未锁批行）——逐单一次批更（单 900 两行
            //   一批、单 901 一行一批，明细先行→单据 CAS 后置写序不变）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> itemWriteCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(itemWriteCaptor.capture()), times(2));
            assertThat(itemWriteCaptor.getAllValues().get(0))
                    .extracting(DispenseItem::getId)
                    .containsExactly(1L, 2L);
            assertThat(itemWriteCaptor.getAllValues().get(1))
                    .extracting(DispenseItem::getId)
                    .containsExactly(3L);
            assertThat(itemWriteCaptor.getAllValues().stream().flatMap(List::stream))
                    .allSatisfy(item -> assertThat(item.getItemStatus()).isEqualTo("CANCELLED"));
            // 锁定释放仅锁批行（batchId 缺位零释放面），发药单同步作废
            verify(drugBatchMapper).releaseLock(eq(55L), any());
            verify(drugBatchMapper).releaseLock(eq(66L), any());
            verify(drugBatchMapper, times(2)).releaseLock(anyLong(), any());
            verify(dispenseMapper).casStatus(900L, "CREATED", "CANCELLED");
            verify(dispenseMapper).casStatus(901L, "PICKING", "CANCELLED");
        }
    }

    @Test
    @DisplayName("order.cancelled 批量写锚定（EX-37）：明细退场逐单一次批更（M 行 M 更收敛 1 批/单） "
            + "+ 锁定释放/单据 CAS 0 行防线逐行保留 + 退场补丁仅携 id+itemStatus")
    void orderCancelledBatchesItemPatchPerDispenseWithRowGuardsKept() {
        DispenseServiceImpl impl = newService();
        // 两处方待作废：R1 挂 CREATED 单（明细两行——未锁批+锁批 55）、R2 挂 PICKING 单（明细一行——锁批 66）
        Prescription voiding1 = rxPendingFee(100L, "R20260918000001");
        voiding1.setStatus("PENDING_DISPENSE");
        Prescription voiding2 = rxPendingFee(101L, "R20260918000002");
        voiding2.setStatus("DISPENSING");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(voiding1, voiding2));
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        when(prescriptionMapper.casStatus(101L, "DISPENSING", "CANCELLED")).thenReturn(1);
        Dispense created = activeDispense("CREATED");
        Dispense picking = activeDispense("PICKING");
        picking.setId(901L);
        picking.setDispenseNo("D20260918000002");
        picking.setRxNo("R20260918000002");
        when(dispenseMapper.selectList(any())).thenReturn(List.of(created, picking));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(normalItem(1L, 900L, null), normalItem(2L, 900L, 55L), normalItem(3L, 901L, 66L)));
        // 锁定释放/单据作废条件更新逐行保留：0 行=批次漂移/并发被抢硬防线（禁批量吞语义）
        when(drugBatchMapper.releaseLock(eq(55L), any())).thenReturn(1);
        when(drugBatchMapper.releaseLock(eq(66L), any())).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "CREATED", "CANCELLED")).thenReturn(1);
        when(dispenseMapper.casStatus(901L, "PICKING", "CANCELLED")).thenReturn(1);

        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            impl.voidUndispensedByRx(List.of("R20260918000001", "R20260918000002"), "退费逆向终态确认");

            verify(drugBatchMapper).releaseLock(eq(55L), any());
            verify(drugBatchMapper).releaseLock(eq(66L), any());
            verify(dispenseMapper).casStatus(900L, "CREATED", "CANCELLED");
            verify(dispenseMapper).casStatus(901L, "PICKING", "CANCELLED");
            // 明细退场逐单一次批更（保持「明细先行→单据 CAS 后置」的原单内写序），旧逐行通道下线
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DispenseItem>> patchesCaptor = ArgumentCaptor.forClass(List.class);
            mockedDb.verify(() -> Db.updateBatchById(patchesCaptor.capture()), times(2));
            verify(dispenseItemMapper, never()).updateById(any(DispenseItem.class));
            assertThat(patchesCaptor.getAllValues()).hasSize(2);
            assertThat(patchesCaptor.getAllValues().get(0))
                    .extracting(DispenseItem::getId)
                    .containsExactly(1L, 2L); // 单 900 两行一批
            assertThat(patchesCaptor.getAllValues().get(1))
                    .extracting(DispenseItem::getId)
                    .containsExactly(3L); // 单 901 一行一批
            // EX-24 补丁纪律：仅携 id+明细状态，读点快照列（批次/数量等）不进 SET
            assertThat(patchesCaptor.getAllValues().stream().flatMap(List::stream))
                    .allSatisfy(patch -> {
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
    @DisplayName("order.cancelled 混合批次脏守卫保持：前序处方作废后遇 ISSUED 活动单仍拒 PH-1009 整事务回滚")
    void orderCancelledKeepsIssuedDirtyGuardInMixedBatch() {
        DispenseServiceImpl impl = newService();
        Prescription voiding = rxPendingFee(100L, "R20260918000001");
        voiding.setStatus("PENDING_DISPENSE");
        Prescription dirty = rxPendingFee(101L, "R20260918000002");
        dirty.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(voiding, dirty));
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        when(prescriptionMapper.casStatus(101L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        Dispense created = activeDispense("CREATED");
        Dispense issued = activeDispense("ISSUED");
        issued.setId(901L);
        issued.setDispenseNo("D20260918000002");
        issued.setRxNo("R20260918000002");
        when(dispenseMapper.selectList(any())).thenReturn(List.of(created, issued));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(normalItem(1L, 900L, null)));
        when(dispenseMapper.casStatus(900L, "CREATED", "CANCELLED")).thenReturn(1);

        // EX-37 桩面补位：前序单明细退场批写先于脏单守卫发生（静态 Db 桩内执行）
        try (MockedStatic<Db> mockedDb = Mockito.mockStatic(Db.class)) {
            assertThatThrownBy(
                            () -> impl.voidUndispensedByRx(List.of("R20260918000001", "R20260918000002"), "退费逆向终态确认"))
                    .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));

            // 前序处方作废写面先行发生（真实库整事务回滚；mock 侧证明逐行序与旧链一致），脏单
            //   零作废零释放（ISSUED 守卫先于明细处置）
            verify(prescriptionMapper).casStatus(100L, "PENDING_DISPENSE", "CANCELLED");
            verify(dispenseMapper).casStatus(900L, "CREATED", "CANCELLED");
            verify(dispenseMapper, never()).casStatus(eq(901L), any(), any());
            verify(drugBatchMapper, never()).releaseLock(anyLong(), any());
        }
    }

    @Test
    @DisplayName("order.cancelled 空清单兜底：零查询零写面直返（listener 侧已拦，service 侧兜底对齐原空循环语义）")
    void voidUndispensedByRxSkipsEmptyListWithoutAnyQueryOrWrite() {
        DispenseServiceImpl impl = newService();

        impl.voidUndispensedByRx(List.of(), "退费逆向终态确认");

        // 空清单守卫：三级级联批查均不发起（零查询零写面）
        verifyNoInteractions(prescriptionMapper, dispenseMapper, dispenseItemMapper, drugBatchMapper);
    }

    @Test
    @DisplayName("order.cancelled 处方批查重复键防御：同 rxNo 重复行保留首行（CAS 作废取首行 id/状态）")
    void voidUndispensedByRxKeepsFirstPrescriptionRowOnDuplicateRxNo() {
        DispenseServiceImpl impl = newService();
        // 处方批查结果集同 rxNo 两行（uk_rx_no 脏数据防御面）：首行 PENDING_DISPENSE（作废域内）、
        //   次行已作废（若被保留则幂等直返零写）——toMap 重复键保留首行，逐号判定以首行状态为准
        Prescription first = rxPendingFee(100L, "R20260918000001");
        first.setStatus("PENDING_DISPENSE");
        Prescription duplicate = rxPendingFee(201L, "R20260918000001");
        duplicate.setStatus("CANCELLED");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(first, duplicate));
        when(prescriptionMapper.casStatus(100L, "PENDING_DISPENSE", "CANCELLED"))
                .thenReturn(1);
        // 首行保留→活动单键集批查照常发起，无活动单（空结果=原空返回形态，零退场写面）
        when(dispenseMapper.selectList(any())).thenReturn(List.of());

        impl.voidUndispensedByRx(List.of("R20260918000001"), "退费逆向终态确认");

        // 首行语义断言：CAS 作废取首行 id=100/状态 PENDING_DISPENSE（次行 id=201 零触达；
        //   若次行 CANCELLED 被保留则幂等直返零 CAS，本断言即失败）
        verify(prescriptionMapper).casStatus(100L, "PENDING_DISPENSE", "CANCELLED");
        verify(prescriptionMapper, never()).casStatus(eq(201L), anyString(), anyString());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("acceptReturn 住院形态分流（P2 PR-3 Task 8）：dispensePlanNo 非空整体委托摆药计划服务，门诊链路零触达")
    void acceptReturnDelegatesInpatientFormToPlanService() {
        DispenseServiceImpl impl = newService();
        DispenseReturnRequest inpatientForm = new DispenseReturnRequest(
                null,
                null,
                null,
                "DP2026100200001",
                List.of(new DispenseReturnRequest.InpatientReturnLine("1", "1", null)));

        impl.acceptReturn(inpatientForm);

        // 住院形态整体委托（DELIVERED 退药+回补+returned 事件归 DispensePlanServiceImpl 承载）
        verify(dispensePlanService).acceptInpatientReturn(inpatientForm);
        // 门诊链路（处方/调剂/批次/明细）零触达
        verifyNoInteractions(dispenseMapper, dispenseItemMapper, prescriptionMapper, drugBatchMapper);
    }

    @Test
    @DisplayName("acceptReturn 门诊形态入参守卫（DTO 注解应用层化）：缺单号/缺退药行 PH-1013 拒（禁 NPE 直穿）")
    void acceptReturnRejectsIncompleteOutpatientForm() {
        DispenseServiceImpl impl = newService();

        // 缺调剂单号（dispenseNo 空）
        assertThatThrownBy(() ->
                        impl.acceptReturn(new DispenseReturnRequest(null, "ISSUED_RETURN", List.of(), null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED))
                .hasMessageContaining("门诊形态缺调剂单号或退药行");

        // 缺退药行（items null——原 @NotEmpty 应用层化后的空面守卫）
        assertThatThrownBy(() -> impl.acceptReturn(
                        new DispenseReturnRequest("D20260918000001", "ISSUED_RETURN", null, null, null)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        // 守卫先行：单据查询零触达
        verifyNoInteractions(dispenseMapper);
    }
}
