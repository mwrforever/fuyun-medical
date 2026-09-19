package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.DispenseItem;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 发药服务单测（charged 放行 / fee.created 迁移两入口）：状态 CAS + 幂等重读定性 +
 * 发药单明细入队；调剂三段用例归 DispenseThreeStepTest（Task 6），退药用例随 Task 7 追加。
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

    /** Task 6 起构造器扩九参：调剂三段依赖补位（放行/费用链两入口不触达，仅满足构造） */
    @Mock
    private IBatchSelectService batchSelectService;

    @Mock
    private ApplicationEventPublisher events;

    /** Task 10 起构造器扩十参：主数据读侧缓存补位（占用查询读侧归一，本类两入口不触达） */
    @Mock
    private com.fuyun.pharmacy.cache.PharmacyMasterDataCache masterDataCache;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DispenseItem.class);
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体均须手工注册表信息（放行/入队两读面）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Prescription.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrescriptionItem.class);
    }

    private DispenseServiceImpl newService() {
        // 构造器十参直注（Task 6 起扩三参、Task 10 扩第十参 masterDataCache；objectMapper 用真实例，与本域三段单测同款）
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

    private Prescription rxPendingFee(long id) {
        Prescription rx = new Prescription();
        rx.setId(id);
        rx.setRxNo("R20260918000001");
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

    @Test
    @DisplayName("charged 放行：PENDING_FEE→PENDING_DISPENSE 且建 CREATED 发药单+明细入队")
    void releaseByVisitMarksPrescriptionAndQueuesDispense() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(1);
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of(rxItem(1L, 100L)));
        when(dispenseMapper.insert(any(Dispense.class))).thenAnswer(inv -> {
            inv.getArgument(0, Dispense.class).setId(900L);
            return 1;
        });

        impl.releaseByVisit("O2026091800001");

        ArgumentCaptor<Dispense> dispenseCaptor = ArgumentCaptor.forClass(Dispense.class);
        verify(dispenseMapper).insert(dispenseCaptor.capture());
        Dispense created = dispenseCaptor.getValue();
        assertThat(created.getStatus()).isEqualTo("CREATED");
        assertThat(created.getRxNo()).isEqualTo("R20260918000001");
        assertThat(created.getDispenseType()).isEqualTo("OUTPATIENT");
        assertThat(created.getStorehouse()).isEqualTo("OUTP_PHARM");
        ArgumentCaptor<DispenseItem> itemCaptor = ArgumentCaptor.forClass(DispenseItem.class);
        verify(dispenseItemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getRequestedQty()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("charged 重复投递幂等：处方已放行（CAS 0 行重读已达标）不再建单")
    void releaseByVisitIsIdempotentWhenAlreadyReleased() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L);
        rx.setStatus("PENDING_DISPENSE");
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(0);
        when(prescriptionMapper.selectById(100L)).thenReturn(rx);

        impl.releaseByVisit("O2026091800001");

        verify(dispenseMapper, never()).insert(any(Dispense.class));
    }

    @Test
    @DisplayName("fee.created 迁移：处方通道 billingKey 解析 rxNo 后 APPROVED→PENDING_FEE")
    void markPendingFeeTransitionsApprovedPrescription() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L);
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
    @DisplayName("charged 状态漂移跳过：CAS 0 行且重读非放行态（如 CANCELLED）零建单（warn 分支覆盖）")
    void releaseByVisitSkipsDriftedPrescriptionWithoutQueueing() {
        DispenseServiceImpl impl = newService();
        Prescription rx = rxPendingFee(100L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rx));
        when(prescriptionMapper.casStatus(100L, "PENDING_FEE", "PENDING_DISPENSE"))
                .thenReturn(0);
        Prescription drifted = rxPendingFee(100L);
        drifted.setStatus("CANCELLED");
        when(prescriptionMapper.selectById(100L)).thenReturn(drifted);

        impl.releaseByVisit("O2026091800001");

        verify(dispenseMapper, never()).insert(any(Dispense.class));
        verify(dispenseItemMapper, never()).insert(any(DispenseItem.class));
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
        Prescription rx = rxPendingFee(100L);
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
        Prescription rx = rxPendingFee(100L);
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(prescriptionMapper.casStatus(100L, "APPROVED", "PENDING_FEE")).thenReturn(0);
        Prescription drifted = rxPendingFee(100L);
        drifted.setStatus("CANCELLED");
        when(prescriptionMapper.selectById(100L)).thenReturn(drifted);

        impl.markPendingFee("700101|R20260918000001|PRESCRIPTION_EFFECTIVE|55|2026-09-18");

        verify(prescriptionMapper).casStatus(100L, "APPROVED", "PENDING_FEE");
    }
}
