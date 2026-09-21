package com.fuyun.pharmacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
    private StockLedgerMapper stockLedgerMapper;

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
        // 构造器十一参直注（Task 6 起扩 batchSelectService/events/objectMapper、Task 10 扩第十参
        // masterDataCache、Task 11 扩第十一参 settlementQueryPort，objectMapper 用真实例承载 JSON 读写）；
        // ServiceImpl 继承字段 baseMapper 反射注入（Global Constraints 单测范式）
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

    @Test
    @DisplayName("实物退全量：追溯码核验通过→批次回补+回补流水（正数）→发药单 FULL_RETURNED→returned 事件 fullReturn=true")
    void acceptReturnRestocksBatchAndMarksFullReturned() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("2"))).thenReturn(1);
        when(dispenseMapper.casStatus(900L, "ISSUED", "FULL_RETURNED")).thenReturn(1);
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("2")))
                .thenReturn(1);

        impl.acceptReturn(issuedReturn("2", List.of("TR-A1B2", "TR-C3D4")));

        verify(drugBatchMapper).restock(55L, new BigDecimal("2"));
        // 数据库写操作断言：处方明细退药累计回写（V701 returned_quantity 落地点，occupancy 数据源）
        verify(prescriptionItemMapper).accumulateReturnedQuantity(10L, new BigDecimal("2"));
        ArgumentCaptor<StockLedger> ledgerCaptor = ArgumentCaptor.forClass(StockLedger.class);
        verify(stockLedgerMapper).insert(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getAction()).isEqualTo("RETURN_RESTOCK");
        assertThat(ledgerCaptor.getValue().getQuantity()).isEqualByComparingTo("2");
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
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("1")))
                .thenReturn(1);

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
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("1")))
                .thenReturn(1);

        impl.acceptReturn(issuedReturn("1", List.of("TR-C3D4")));

        // 累计形态断言：续退时点再次按增量回写（returned_quantity = returned_quantity + 本次退量），
        //   两次部分退累计 = 全量——occupancy returnedQuantity 由此收敛到实发数
        verify(prescriptionItemMapper).accumulateReturnedQuantity(10L, new BigDecimal("1"));
        ArgumentCaptor<DispenseItem> itemCaptor = ArgumentCaptor.forClass(DispenseItem.class);
        verify(dispenseItemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getReturnedQty()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("处方明细回写零行：accumulateReturnedQuantity 0 行拒 PH-1013（明细脏数据整事务回滚）")
    void acceptReturnRejectsWhenRxItemAccumulateAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(1);
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);
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
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);

        impl.acceptReturn(new DispenseReturnRequest(
                "D20260918000001",
                "DISPENSING_CANCEL",
                List.of(new DispenseReturnRequest.ReturnLine("10", "2", null))));

        verify(drugBatchMapper).releaseLock(55L, new BigDecimal("2"));
        verify(stockLedgerMapper, never()).insert(any(StockLedger.class)); // 锁定数非数量流水
        verify(events, never()).publishEvent(any()); // 未出库不发 returned
        ArgumentCaptor<DispenseItem> itemCaptor = ArgumentCaptor.forClass(DispenseItem.class);
        verify(dispenseItemMapper).updateById(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getItemStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("refund.approved 单据化镜像：同患者两 DISPENSED 处方仅反查清单内处方镜像 FULL_RETURNED（注记⑦收口）")
    void confirmRefundTerminalByRxMirrorsOnlyListedRx() {
        DispenseServiceImpl impl = newService();
        // 同患者两处方均 DISPENSED 且发药单均已 FULL_RETURNED——本次反查清单仅覆盖其一
        Prescription listed = new Prescription();
        listed.setId(100L);
        listed.setRxNo("R20260918000001");
        listed.setPatientId(700101L);
        listed.setStatus("DISPENSED");
        Prescription unlisted = new Prescription();
        unlisted.setId(101L);
        unlisted.setRxNo("R20260918000002");
        unlisted.setPatientId(700101L);
        unlisted.setStatus("DISPENSED");
        when(prescriptionMapper.selectOne(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment(); // MP 条件参数在 getSqlSegment 惰性求值时才写入参数表
            return wrapper.getParamNameValuePairs().containsValue("R20260918000001") ? listed : unlisted;
        });
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("FULL_RETURNED"));
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
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);

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
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(dispenseMapper.selectOne(any())).thenReturn(null);

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
        when(prescriptionMapper.selectOne(any())).thenReturn(rx);
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
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

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("2", List.of("TR-A1B2", "TR-C3D4"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(drugBatchMapper, never()).restock(anyLong(), any());
        verify(stockLedgerMapper, never()).insert(any(StockLedger.class));
    }

    @Test
    @DisplayName("退药数量非数字串拒 PH-1016（400 显式拒，禁 NumberFormatException 直穿 500——零资金动作/零状态迁移）")
    void acceptReturnRejectsNonNumericReturnQuantityAsPh1016() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("两盒", List.of("TR-A1B2"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.NUMERIC_FIELD_MALFORMED));
        // 格式守卫在全部写面之前拦截：零回补/零流水/零回写/零终态迁移/零事件
        verify(drugBatchMapper, never()).restock(anyLong(), any());
        verify(stockLedgerMapper, never()).insert(any(StockLedger.class));
        verify(prescriptionItemMapper, never()).accumulateReturnedQuantity(anyLong(), any());
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("批次回补零行：restock 条件更新 0 行拒 PH-1013（批次状态漂移，整事务回滚）")
    void acceptReturnRejectsWhenRestockAffectsZeroRows() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any())).thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("1"))).thenReturn(0);

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("1", List.of("TR-A1B2"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.RETURN_STATE_NOT_ALLOWED));
        verify(stockLedgerMapper, never()).insert(any(StockLedger.class));
        verify(dispenseMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("退药终态并发被抢：casStatus(ISSUED→FULL_RETURNED) 0 行拒 PH-1009（returned 事件后置锚、违例禁出）")
    void acceptReturnRejectsWhenTerminalCasAffectsZeroRowsAsPh1009() {
        DispenseServiceImpl impl = newService();
        when(dispenseMapper.selectOne(any())).thenReturn(dispense("ISSUED"));
        when(dispenseItemMapper.selectList(any()))
                .thenReturn(List.of(issuedItem("2", "0", "[\"TR-A1B2\",\"TR-C3D4\"]")));
        when(drugBatchMapper.restock(55L, new BigDecimal("2"))).thenReturn(1);
        when(stockLedgerMapper.insert(any(StockLedger.class))).thenReturn(1);
        when(dispenseItemMapper.updateById(any(DispenseItem.class))).thenReturn(1);
        // 退药累计回写打桩命中（本次改动新增写面，置于终态 CAS 之前）——本用例靶点为终态并发被抢分支
        when(prescriptionItemMapper.accumulateReturnedQuantity(10L, new BigDecimal("2")))
                .thenReturn(1);
        when(dispenseMapper.casStatus(900L, "ISSUED", "FULL_RETURNED")).thenReturn(0);

        assertThatThrownBy(() -> impl.acceptReturn(issuedReturn("2", List.of("TR-A1B2", "TR-C3D4"))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(PharmacyErrorCode.DISPENSE_STATE_NOT_ALLOWED));
        verify(events, never()).publishEvent(any()); // 事件发布后置于终态迁移，并发被抢即整事务回滚
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
        when(prescriptionMapper.selectOne(any())).thenReturn(null);

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
        when(prescriptionMapper.selectOne(any())).thenReturn(pending);

        impl.confirmRefundTerminalByRx(List.of("R20260918000001"));

        verify(prescriptionMapper, never()).casStatus(anyLong(), anyString(), anyString());
    }
}
