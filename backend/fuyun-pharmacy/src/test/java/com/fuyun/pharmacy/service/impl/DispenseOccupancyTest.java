package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.pharmacy.cache.PharmacyMasterDataCache;
import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import com.fuyun.pharmacy.mapper.DispenseItemMapper;
import com.fuyun.pharmacy.mapper.DispenseMapper;
import com.fuyun.pharmacy.mapper.DrugBatchMapper;
import com.fuyun.pharmacy.mapper.PrescriptionItemMapper;
import com.fuyun.pharmacy.mapper.PrescriptionMapper;
import com.fuyun.pharmacy.mapper.StockLedgerMapper;
import com.fuyun.pharmacy.service.IBatchSelectService;
import com.fuyun.pharmacy.vo.OccupancyVO;
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
 * 占用查询单测（GET /medication-occupancy，供 M13 位）：处方×明细×发药单三维投影、
 * 读侧患者归一（merged 从档入参归主档查询）、未配药处方占用行可见（退费前置）、
 * 可选参数缺位不附加谓词。billing 不切注记维持——BILL-1017 硬前置仍走 exec_occupy_status 列口径。
 */
@ExtendWith(MockitoExtension.class)
class DispenseOccupancyTest {

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

    /** Task 10 起构造器扩十参：读侧患者归一缓存补位（占用查询唯一消费方） */
    @Mock
    private PharmacyMasterDataCache masterDataCache;

    @BeforeAll
    static void initTableInfo() {
        // MP 3.5.17 单测范式：lambdaQuery 触达的实体均须手工注册表信息（谓词断言三实体）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Dispense.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Prescription.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PrescriptionItem.class);
    }

    private DispenseServiceImpl newService() {
        // 构造器十参直注（Task 10 起第十参 masterDataCache；objectMapper 用真实例，与本域单测同款）
        DispenseServiceImpl impl = new DispenseServiceImpl(
                dispenseMapper,
                dispenseItemMapper,
                drugBatchMapper,
                stockLedgerMapper,
                prescriptionMapper,
                prescriptionItemMapper,
                batchSelectService,
                events,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                masterDataCache);
        ReflectionTestUtils.setField(impl, "baseMapper", dispenseMapper);
        return impl;
    }

    private Prescription rxDISPENSED() {
        Prescription rx = new Prescription();
        rx.setId(100L);
        rx.setRxNo("R20260918000001");
        rx.setPatientId(700101L);
        rx.setVisitId("O2026091800001");
        rx.setStatus("DISPENSED");
        return rx;
    }

    private PrescriptionItem rxItem() {
        PrescriptionItem item = new PrescriptionItem();
        item.setId(1L);
        item.setPrescriptionId(100L);
        item.setItemCode("C0131230900157");
        item.setQuantity(new BigDecimal("2"));
        item.setReturnedQuantity(new BigDecimal("1"));
        return item;
    }

    private Dispense dispenseIssued() {
        Dispense d = new Dispense();
        d.setId(900L);
        d.setDispenseNo("D20260918000001");
        d.setRxNo("R20260918000001");
        d.setStatus("ISSUED");
        return d;
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表） */
    private LambdaQueryWrapper<Prescription> capturedRxWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Prescription>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(prescriptionMapper).selectList(captor.capture());
        LambdaQueryWrapper<Prescription> wrapper = (LambdaQueryWrapper<Prescription>) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper;
    }

    @Test
    @DisplayName("占用三维投影：处方×明细×发药单联立出参（数量 DECIMAL string 承载）")
    void occupancyJoinsPrescriptionItemAndDispense() {
        when(masterDataCache.resolveSurvivor(700101L)).thenReturn(700101L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rxDISPENSED()));
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of(rxItem()));
        when(dispenseMapper.selectOne(any())).thenReturn(dispenseIssued());

        List<OccupancyVO> rows = newService().occupancy(700101L, "O2026091800001", "C0131230900157");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rxNo()).isEqualTo("R20260918000001");
        assertThat(rows.get(0).itemCode()).isEqualTo("C0131230900157");
        assertThat(rows.get(0).prescriptionStatus()).isEqualTo("DISPENSED");
        assertThat(rows.get(0).dispenseNo()).isEqualTo("D20260918000001");
        assertThat(rows.get(0).dispenseStatus()).isEqualTo("ISSUED");
        assertThat(rows.get(0).issuedQuantity()).isEqualTo("2");
        assertThat(rows.get(0).returnedQuantity()).isEqualTo("1");
    }

    @Test
    @DisplayName("读侧归一：merged 从档 12 入参经缓存归一主档 7 进查询谓词（M-25 订阅闭环）")
    void occupancyNormalizesMergedPatient() {
        when(masterDataCache.resolveSurvivor(12L)).thenReturn(7L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of());

        newService().occupancy(12L, null, null);

        LambdaQueryWrapper<Prescription> wrapper = capturedRxWrapper();
        assertThat(wrapper.getSqlSegment()).contains("patient_id");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L);
    }

    @Test
    @DisplayName("未配药处方可见：发药单缺位时占用行仍出（单据与数量字段为空，退费前置依据）")
    void occupancyKeepsUnprescribedRowsWithoutDispense() {
        when(masterDataCache.resolveSurvivor(700101L)).thenReturn(700101L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rxDISPENSED()));
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of(rxItem()));
        when(dispenseMapper.selectOne(any())).thenReturn(null);

        List<OccupancyVO> rows = newService().occupancy(700101L, "O2026091800001", "C0131230900157");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).rxNo()).isEqualTo("R20260918000001");
        assertThat(rows.get(0).itemCode()).isEqualTo("C0131230900157");
        assertThat(rows.get(0).dispenseNo()).isNull();
        assertThat(rows.get(0).dispenseStatus()).isNull();
        assertThat(rows.get(0).issuedQuantity()).isNull();
        assertThat(rows.get(0).returnedQuantity()).isNull();
    }

    @Test
    @DisplayName("可选参数缺位：visitId/itemCode 传 null 谓词不附加（filter 分支行覆盖）")
    void occupancySkipsFiltersWhenOptionalParamsBlank() {
        when(masterDataCache.resolveSurvivor(700101L)).thenReturn(700101L);
        when(prescriptionMapper.selectList(any())).thenReturn(List.of(rxDISPENSED()));
        when(prescriptionItemMapper.selectList(any())).thenReturn(List.of(rxItem()));
        when(dispenseMapper.selectOne(any())).thenReturn(dispenseIssued());

        List<OccupancyVO> rows = newService().occupancy(700101L, null, null);

        assertThat(rows).hasSize(1);
        LambdaQueryWrapper<Prescription> rxWrapper = capturedRxWrapper();
        assertThat(rxWrapper.getSqlSegment()).doesNotContain("visit_id");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<PrescriptionItem>> itemCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(prescriptionItemMapper).selectList(itemCaptor.capture());
        LambdaQueryWrapper<PrescriptionItem> itemWrapper = (LambdaQueryWrapper<PrescriptionItem>) itemCaptor.getValue();
        assertThat(itemWrapper.getSqlSegment()).doesNotContain("item_code");
    }
}
