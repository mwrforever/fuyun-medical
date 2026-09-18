package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.billing.dto.InsurancePreSettleCommand;
import com.fuyun.billing.dto.InsurancePreSettleResult;
import com.fuyun.billing.dto.PaymentLine;
import com.fuyun.billing.dto.SettleRequest;
import com.fuyun.billing.dto.SettlementPreviewRequest;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.entity.InsuranceCallLog;
import com.fuyun.billing.entity.Settlement;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.InsuranceCallStatus;
import com.fuyun.billing.enums.PayerType;
import com.fuyun.billing.enums.PaymentMethod;
import com.fuyun.billing.enums.SettlementStatus;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.gateway.InsuranceGateway;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.mapper.SettlementMapper;
import com.fuyun.billing.service.IInsuranceCallLogService;
import com.fuyun.billing.vo.SettlementPreviewVO;
import com.fuyun.billing.vo.SettlementVO;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.CardAccountLedger;
import com.fuyun.patient.api.CardTxnRecord;
import com.fuyun.patient.api.CardTxnType;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
 * 结算服务单测（资金红线：两层金额勾稽、settleNo 幂等终态直返、就诊卡台账记账同事务、
 * settlement.completed 事件登记、payment_details JSON 列形态同源冻结）。
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceImplTest {

    /** 门诊就诊号夹具（O 前缀 → settleType=OUT 派生） */
    private static final String VISIT = "O2026091700001";

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private CardAccountLedger cardAccountLedger;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private InsuranceGateway insuranceGateway;

    @Mock
    private IInsuranceCallLogService callLogService;

    private SettlementServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Settlement.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
    }

    @BeforeEach
    void setUp() {
        service =
                new SettlementServiceImpl(feeRecordMapper, cardAccountLedger, events, insuranceGateway, callLogService);
        ReflectionTestUtils.setField(service, "baseMapper", settlementMapper);
        ReflectionTestUtils.setField(service, "entityClass", Settlement.class);
    }

    private Settlement settlement(long id, String settleNo, SettlementStatus status, long totalAmount) {
        Settlement st = new Settlement();
        st.setId(id);
        st.setSettleNo(settleNo);
        st.setPatientId(7L);
        st.setVisitId(VISIT);
        st.setSettleType(VisitType.OUT);
        st.setPayerType(PayerType.SELF_PAY);
        st.setTotalAmount(totalAmount);
        st.setStatus(status);
        return st;
    }

    private FeeRecord fee(long id, long amount, OffsetDateTime chargedAt) {
        FeeRecord fee = new FeeRecord();
        fee.setId(id);
        fee.setVisitId(VISIT);
        fee.setAmount(amount);
        fee.setChargedAt(chargedAt);
        fee.setStatus(FeeStatus.PENDING);
        return fee;
    }

    @Test
    @DisplayName("自费预结算：PENDING 费用求和为总额、DRAFT 草稿落库、费用期起止取计费时刻极值")
    void previewSelfPaySumsFeesAndCreatesPresettableDraft() {
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        fee(1L, 3000L, OffsetDateTime.parse("2026-09-17T09:30+08:00")),
                        fee(2L, 2000L, OffsetDateTime.parse("2026-09-17T08:00+08:00"))));
        // 模拟 MP ASSIGN_ID 回填：insert 时给实体置 id
        when(settlementMapper.insert(any(Settlement.class))).thenAnswer(inv -> {
            inv.getArgument(0, Settlement.class).setId(900L);
            return 1;
        });

        SettlementPreviewVO vo = service.preview(new SettlementPreviewRequest(7L, VISIT, PayerType.SELF_PAY));

        // 结算单落库断言：总额=费用求和（服务端算，不采信前端）、自费=全额、状态 DRAFT、类型按就诊号前缀派生
        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(captor.capture());
        Settlement row = captor.getValue();
        assertThat(row.getSettleNo()).startsWith("S");
        assertThat(row.getTotalAmount()).isEqualTo(5000L); // 3000+2000 服务端求和
        assertThat(row.getSelfExpenseAmount()).isEqualTo(5000L); // 自费=全额，无医保拆分
        assertThat(row.getPooledAmount()).isNull();
        assertThat(row.getStatus()).isEqualTo(SettlementStatus.DRAFT);
        assertThat(row.getSettleType()).isEqualTo(VisitType.OUT); // O 前缀派生
        assertThat(row.getFeeStart()).isEqualTo(OffsetDateTime.parse("2026-09-17T08:00+08:00")); // 计费时刻极小值
        assertThat(row.getFeeEnd()).isEqualTo(OffsetDateTime.parse("2026-09-17T09:30+08:00")); // 计费时刻极大值
        assertThat(vo.settleNo()).isEqualTo(row.getSettleNo());
        assertThat(vo.totalAmount()).isEqualTo(5000L);
        assertThat(vo.status()).isEqualTo("DRAFT");
        // 费用清单 SQL 守卫钉死：等值条件必须落在 visit_id+status 列且携带本次就诊号与 PENDING（防跨就诊串单）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FeeRecord>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<FeeRecord> wrapper = (LambdaQueryWrapper<FeeRecord>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("visit_id").contains("status");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, FeeStatus.PENDING);
    }

    @Test
    @DisplayName("空单预结算：无 PENDING 费用 BILL-1008 显式拒（禁产出零额结算单）")
    void previewRejectsEmptyFeesAsBill1008() {
        when(feeRecordMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.preview(new SettlementPreviewRequest(7L, VISIT, PayerType.SELF_PAY)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE));
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    /** 已贯标费用行夹具（对照编码快照在位，可参与医保结算） */
    private FeeRecord mappedFee(long id, long amount, OffsetDateTime chargedAt) {
        FeeRecord fee = fee(id, amount, chargedAt);
        fee.setNhsaCodeSnapshot("NHBZ-TREAT-001");
        return fee;
    }

    @Test
    @DisplayName("医保预结算：贯标校验过→网关拆分回填五列并 PRESETTLED 锁价、2102 留痕行落 SUCCESS")
    void previewInsurancePayerLocksSplitAndRecords2102CallLog() {
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(mappedFee(1L, 10000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        // 模拟网关回执：五拆分（勾稽恒等由适应器单测锁定，此处服务侧取回执回填+二次勾稽）
        when(insuranceGateway.preSettle(any()))
                .thenReturn(
                        new InsurancePreSettleResult(10000L, 6000L, 2000L, 2000L, 0L, 0L, "SIM-S100", "SIM-2026Q3"));
        // 模拟 MP ASSIGN_ID 回填：insert 时给实体置 id
        when(settlementMapper.insert(any(Settlement.class))).thenAnswer(inv -> {
            inv.getArgument(0, Settlement.class).setId(900L);
            return 1;
        });

        SettlementPreviewVO vo = service.preview(new SettlementPreviewRequest(7L, VISIT, PayerType.CITY_INS));

        // 结算单锁价断言：五拆分逐列按回执回填（本地不自行计算，红线 1）、状态 PRESETTLED、中心流水号/目录版本回执同源
        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(captor.capture());
        Settlement row = captor.getValue();
        assertThat(row.getStatus()).isEqualTo(SettlementStatus.PRESETTLED);
        assertThat(row.getTotalAmount()).isEqualTo(10000L);
        assertThat(row.getPooledAmount()).isEqualTo(6000L);
        assertThat(row.getAcctPayAmount()).isEqualTo(2000L);
        assertThat(row.getSelfPayAmount()).isEqualTo(2000L);
        assertThat(row.getSelfExpenseAmount()).isZero();
        assertThat(row.getPreSelfPayAmount()).isZero();
        assertThat(row.getInsSettleNo()).isEqualTo("SIM-S100");
        assertThat(row.getCatalogVersion()).isEqualTo("SIM-2026Q3");
        assertThat(vo.status()).isEqualTo("PRESETTLED");
        // 网关命令守卫钉死：费用行快照列投影 + 幂等键=结算编号 + 预览前计算 settlementDraftId=0
        ArgumentCaptor<InsurancePreSettleCommand> cmdCaptor = ArgumentCaptor.forClass(InsurancePreSettleCommand.class);
        verify(insuranceGateway).preSettle(cmdCaptor.capture());
        InsurancePreSettleCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.settlementDraftId()).isZero();
        assertThat(cmd.visitId()).isEqualTo(VISIT);
        assertThat(cmd.idempotencyKey()).isEqualTo(row.getSettleNo()); // 幂等键与结算编号同源
        assertThat(cmd.lines()).hasSize(1);
        assertThat(cmd.lines().get(0).nhsaCode()).isEqualTo("NHBZ-TREAT-001");
        assertThat(cmd.lines().get(0).amount()).isEqualTo(10000L);
        // 2102 业务级留痕断言（Task 12 Step 3b 交接口径：txn_code='2102'、status='SUCCESS'）
        ArgumentCaptor<InsuranceCallLog> logCaptor = ArgumentCaptor.forClass(InsuranceCallLog.class);
        verify(callLogService).record(logCaptor.capture());
        InsuranceCallLog logRow = logCaptor.getValue();
        assertThat(logRow.getTxnCode()).isEqualTo("2102");
        assertThat(logRow.getVisitId()).isEqualTo(VISIT);
        assertThat(logRow.getStatus()).isEqualTo(InsuranceCallStatus.SUCCESS);
        assertThat(logRow.getResultCode()).isEqualTo("0000");
        assertThat(logRow.getRequestDigest()).isEqualTo("preview|total=10000");
        assertThat(logRow.getResponseDigest()).contains("SIM-S100");
        assertThat(logRow.getResponseDigest().length()).isLessThanOrEqualTo(512); // 回执摘要列宽钳制口径
        assertThat(logRow.getCenterSerialNo()).isEqualTo("SIM-S100");
    }

    @Test
    @DisplayName("医保 payer 未贯标费用行：BILL-1006 贯标硬校验显式拒，网关与留痕零触碰")
    void previewRejectsUnmappedFeeOnInsurancePayerAsBill1006() {
        // 未贯标行：计费时点无 ACTIVE 对照 → nhsaCodeSnapshot=null（V601 唯一 ACTIVE 语义冻结进快照列）
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 5000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));

        assertThatThrownBy(() -> service.preview(new SettlementPreviewRequest(7L, VISIT, PayerType.CITY_INS)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.INSURANCE_MAPPING_MISSING));
        // 拒即零触碰：禁未对照项目串入医保基金，网关不调、留痕不落、结算单不产出
        verifyNoInteractions(insuranceGateway, callLogService);
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    @DisplayName("医保拆分勾稽不平：五拆分和≠总额 BILL-1016 拒（红线 1，禁产出半截结算单）")
    void previewRejectsSplitMismatchAsBill1016() {
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(mappedFee(1L, 10000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        // 模拟通道异常回执：五拆分和=9000 ≠ 总额 10000（真实通道同口径拒）
        when(insuranceGateway.preSettle(any()))
                .thenReturn(
                        new InsurancePreSettleResult(10000L, 6000L, 2000L, 1000L, 0L, 0L, "SIM-S100", "SIM-2026Q3"));

        assertThatThrownBy(() -> service.preview(new SettlementPreviewRequest(7L, VISIT, PayerType.CITY_INS)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.AMOUNT_MISMATCH));
        // 不平即拒：留痕不落、结算单不产出
        verify(callLogService, never()).record(any());
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    @DisplayName("正式结算：CAS 抢锚落终态、费用条件更新迁移、发 settlement.completed（零全实体 updateById）")
    void settleMarksFeesSettledAndPublishsCompleted() {
        Settlement st = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        fee(1L, 3000L, OffsetDateTime.parse("2026-09-17T09:30+08:00")),
                        fee(2L, 2000L, OffsetDateTime.parse("2026-09-17T08:00+08:00"))));
        // CAS 抢锚成功 + 费用行全量迁移（并发语义桩：两语句均命中满额行数）
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(1);
        when(feeRecordMapper.casMarkFeesSettled(anyLong(), any())).thenReturn(2);

        SettlementVO vo =
                service.settle(new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null))));

        // 结算锚 CAS 断言：终态三字段同语句落库（status/settled_at/payment_details），禁全实体改写
        ArgumentCaptor<OffsetDateTime> settledAtCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
        verify(settlementMapper).casMarkSettled(eq(900L), settledAtCaptor.capture(), detailsCaptor.capture());
        assertThat(settledAtCaptor.getValue()).isNotNull();
        assertThat(detailsCaptor.getValue()).isEqualTo("[{\"method\":\"CASH\",\"amount\":5000,\"channelRef\":null}]");
        assertThat(vo.id()).isEqualTo(900L);
        assertThat(vo.status()).isEqualTo("SETTLED");
        assertThat(vo.settledAt()).isNotNull();
        // 费用行条件更新断言：仅 PENDING 行迁移并回填结算引用（id 集与勾稽读侧同源）
        verify(feeRecordMapper).casMarkFeesSettled(900L, List.of(1L, 2L));
        // 并发收口后费用/结算单均不走全实体 updateById（读-校验-写 TOCTOU 写点已根除）
        verify(feeRecordMapper, never()).updateById(any(FeeRecord.class));
        verify(settlementMapper, never()).updateById(any(Settlement.class));
        // 事件断言走冻结机制：事务内应用事件（AFTER_COMMIT 出 MQ 归 BillingEventPublisher，Task 10 锁定）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.settlement.completed");
        SettlementCompletedPayload payload = (SettlementCompletedPayload) published.payload();
        assertThat(payload.settlementId()).isEqualTo(900L);
        assertThat(payload.settleNo()).isEqualTo("S100");
        assertThat(payload.totalAmount()).isEqualTo(5000L);
        assertThat(payload.settleType()).isEqualTo("OUT");
        assertThat(payload.payerType()).isEqualTo("SELF_PAY");
        // 裁决①：纯 CASH 支付无 CARD_BALANCE 行，不触就诊卡台账
        verifyNoInteractions(cardAccountLedger);
        // 结算单定位 SQL 守卫钉死：等值条件必须落在 settle_no 列且携带请求结算编号（防全表误配）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Settlement>> noCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(settlementMapper).selectOne(noCaptor.capture());
        LambdaQueryWrapper<Settlement> noWrapper = (LambdaQueryWrapper<Settlement>) noCaptor.getValue();
        assertThat(noWrapper.getSqlSegment()).contains("settle_no");
        assertThat(noWrapper.getParamNameValuePairs().values()).contains("S100");
        // 费用清单 SQL 守卫钉死：visit_id+status 等值且 id 升序（行序=事件行序，A.4.3-17）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<FeeRecord>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(feeRecordMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<FeeRecord> wrapper = (LambdaQueryWrapper<FeeRecord>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment())
                .contains("visit_id")
                .contains("status")
                .containsIgnoringCase("ORDER BY");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(VISIT, FeeStatus.PENDING);
    }

    @Test
    @DisplayName("结算幂等：同 settleNo 已 SETTLED 重放直返，不重复扣费不重复发事件")
    void settleIsIdempotentOnSettledStatus() {
        Settlement st = settlement(900L, "S100", SettlementStatus.SETTLED, 5000L);
        st.setSettledAt(OffsetDateTime.parse("2026-09-17T10:00+08:00"));
        when(settlementMapper.selectOne(any())).thenReturn(st);

        SettlementVO vo =
                service.settle(new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null))));

        assertThat(vo.status()).isEqualTo("SETTLED"); // 终态直返（幂等锚点=settleNo）
        assertThat(vo.settledAt()).isEqualTo("2026-09-17T10:00+08:00"); // 原结算时刻不被改写
        verifyNoInteractions(feeRecordMapper, cardAccountLedger, events);
        verify(settlementMapper, never()).updateById(any(Settlement.class));
    }

    @Test
    @DisplayName("就诊卡支付：CARD_BALANCE 行金额求和为扣额，台账 PAY 记账恰一次（入账后置于 CAS 抢锚成功）")
    void settleCardBalanceCallsLedgerRecordPay() {
        Settlement st = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 5000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(1);
        when(feeRecordMapper.casMarkFeesSettled(anyLong(), any())).thenReturn(1);

        service.settle(new SettleRequest(
                "S100",
                List.of(
                        new PaymentLine(PaymentMethod.CARD_BALANCE, 2000L, "5"),
                        new PaymentLine(PaymentMethod.CARD_BALANCE, 3000L, "5"))));

        // 同卡两行合并一笔出账：扣额=2000+3000=5000，accountId=channelRef 解析，对账关联键=settleNo
        verify(cardAccountLedger, times(1)).record(new CardTxnRecord(5L, CardTxnType.PAY, 5000L, "S100"));
    }

    @Test
    @DisplayName("支付勾稽不平：Σpayments≠结算总额 BILL-1016 拒，先勾稽后动卡（台账与结算单状态零触碰）")
    void settleRejectsWhenAmountReconciliationFails() {
        Settlement st = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);

        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 4000L, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.AMOUNT_MISMATCH));
        // 不平即拒：不动卡、不查费用、不改状态、不发事件（资金动作零触碰）
        verifyNoInteractions(cardAccountLedger, events);
        verify(feeRecordMapper, never()).selectList(any());
        verify(settlementMapper, never()).updateById(any(Settlement.class));
    }

    @Test
    @DisplayName("支付明细落库：paymentDetails 为 [{method,amount,channelRef}] JSON 文本（V603 列形态同源，null 引用保三键）")
    void settlePersistsPaymentDetailsJson() {
        Settlement st = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 5000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(1);
        when(feeRecordMapper.casMarkFeesSettled(anyLong(), any())).thenReturn(1);

        service.settle(new SettleRequest(
                "S100",
                List.of(
                        new PaymentLine(PaymentMethod.CASH, 3000L, null),
                        new PaymentLine(PaymentMethod.CARD_BALANCE, 2000L, "5"))));

        // 逐字符断言列形态：method=code、amount 恒数值（不经全局 Long→String 模块）、channelRef null 用 null 保三键
        ArgumentCaptor<String> detailsCaptor = ArgumentCaptor.forClass(String.class);
        verify(settlementMapper).casMarkSettled(eq(900L), any(), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue())
                .isEqualTo("[{\"method\":\"CASH\",\"amount\":3000,\"channelRef\":null},"
                        + "{\"method\":\"CARD_BALANCE\",\"amount\":2000,\"channelRef\":\"5\"}]");
    }

    @Test
    @DisplayName("缺单结算：settleNo 无命中 BILL-1014 显式拒（404，幂等前置缺行分支）")
    void settleRejectsMissingSettlementAsBill1014() {
        when(settlementMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S404", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.SETTLEMENT_NOT_FOUND));
        verifyNoInteractions(feeRecordMapper, cardAccountLedger, events);
    }

    @Test
    @DisplayName("状态不允许结算：REFUNDED 单再结算 BILL-1015 拒（与 SETTLED 幂等直返分支两分支各立）")
    void settleRejectsSettledStateAsBill1015() {
        Settlement st = settlement(900L, "S100", SettlementStatus.REFUNDED, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);

        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.SETTLEMENT_STATE_NOT_ALLOWED));
        verifyNoInteractions(feeRecordMapper, cardAccountLedger, events);
        verify(settlementMapper, never()).updateById(any(Settlement.class));
    }

    @Test
    @DisplayName("明细勾稽不平：支付已配平但 Σ费用明细≠总额 BILL-1016 拒（勾稽前置纯读校验，CAS 与费用迁移零触碰）")
    void settleRejectsWhenFeeSumMismatchBill1016() {
        Settlement st = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 3000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));

        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.AMOUNT_MISMATCH));
        // 不平即零写拒绝：CAS 抢锚与费用迁移均不触发、事件不发（本用例纯 CASH，台账本就不触）
        verify(settlementMapper, never()).casMarkSettled(anyLong(), any(), any());
        verify(feeRecordMapper, never()).casMarkFeesSettled(anyLong(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("CAS 抢锚输家·幂等分支：并发赢家已提交 SETTLED → 重读直返既有终态（零资金动作零事件）")
    void settleCasLoserReturnsIdempotentWhenConcurrentWinnerCommitted() {
        Settlement draft = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(draft);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 5000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        // CAS 影响行数 0：并发同单赢家已提交置 SETTLED → 重读到终态行（原结算时刻不被改写）
        Settlement settled = settlement(900L, "S100", SettlementStatus.SETTLED, 5000L);
        settled.setSettledAt(OffsetDateTime.parse("2026-09-17T10:00+08:00"));
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(0);
        when(settlementMapper.selectById(900L)).thenReturn(settled);

        SettlementVO vo =
                service.settle(new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null))));

        // 输家幂等直返：与赢家等效（同 settleNo 终态），费用迁移/台账/事件零触碰（根除并发双 PAY）
        assertThat(vo.settleNo()).isEqualTo("S100");
        assertThat(vo.status()).isEqualTo("SETTLED");
        assertThat(vo.settledAt()).isEqualTo("2026-09-17T10:00+08:00");
        verify(feeRecordMapper, never()).casMarkFeesSettled(anyLong(), any());
        verifyNoInteractions(cardAccountLedger, events);
    }

    @Test
    @DisplayName("CAS 抢锚输家·态不符分支：重读非 SETTLED（并发退费已迁移 REFUNDED）→ BILL-1015 拒")
    void settleCasLoserRejectsWhenStatusMigratedElsewhere() {
        Settlement draft = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(draft);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 5000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        // CAS 影响行数 0：重读 REFUNDED（并发路径已迁移）→ 非 DRAFT/PRESETTLED/SETTLED 任一直返态
        Settlement refunded = settlement(900L, "S100", SettlementStatus.REFUNDED, 5000L);
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(0);
        when(settlementMapper.selectById(900L)).thenReturn(refunded);

        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.SETTLEMENT_STATE_NOT_ALLOWED));
        // 抢锚失败即无后续资金动作：费用迁移/台账/事件零触碰
        verify(feeRecordMapper, never()).casMarkFeesSettled(anyLong(), any());
        verifyNoInteractions(cardAccountLedger, events);
    }

    @Test
    @DisplayName("费用行并发被抢：CAS 抢锚成功但条件更新迁移数不足（双单并发抢同批费用）→ BILL-1016 整体回滚")
    void settleRejectsWhenFeeRowsLostToConcurrentSettlement() {
        Settlement draft = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(draft);
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(
                        fee(1L, 3000L, OffsetDateTime.parse("2026-09-17T09:30+08:00")),
                        fee(2L, 2000L, OffsetDateTime.parse("2026-09-17T08:00+08:00"))));
        // 本单锚抢占成功，但两行费用已被并发单迁移一行 → 条件更新仅命中 1 < 期望 2
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(1);
        when(feeRecordMapper.casMarkFeesSettled(anyLong(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CASH, 5000L, null)))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.AMOUNT_MISMATCH));
        // 迁移不足即拒：事件不发（结算锚抢占随调用方事务整体回滚，无中间态）
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("多卡混付拒：两条 CARD_BALANCE 行 channelRef 不一致 BILL-1012（同结算仅允许单张就诊卡）")
    void settleRejectsMixedCardRowsAsBill1012() {
        Settlement st = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(st);

        assertThatThrownBy(() -> service.settle(new SettleRequest(
                        "S100",
                        List.of(
                                new PaymentLine(PaymentMethod.CARD_BALANCE, 2000L, "5"),
                                new PaymentLine(PaymentMethod.CARD_BALANCE, 3000L, "6")))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        // 混付即拒：卡零触碰、结算单零改写
        verifyNoInteractions(cardAccountLedger);
        verify(settlementMapper, never()).updateById(any(Settlement.class));
        verify(feeRecordMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("卡引用解析守卫：CARD_BALANCE 行 channelRef 空白与非数字均 BILL-1012（禁静默错扣/裸 parseLong 出 500）")
    void parseCardAccountIdRejectsBlankAndNonNumeric() {
        Settlement blank = settlement(900L, "S100", SettlementStatus.DRAFT, 5000L);
        Settlement nonNumeric = settlement(901L, "S101", SettlementStatus.DRAFT, 5000L);
        when(settlementMapper.selectOne(any())).thenReturn(blank, nonNumeric);
        // 费用清单读已前置（纯读勾稽先于 CAS 与动卡）：配平费用行 + 抢锚成功，让流程推进至
        //   动卡处的引用解析拒绝点（parseCardAccountId 在 record 调用点，锚抢占成功之后）
        when(feeRecordMapper.selectList(any()))
                .thenReturn(List.of(fee(1L, 5000L, OffsetDateTime.parse("2026-09-17T09:30+08:00"))));
        when(settlementMapper.casMarkSettled(anyLong(), any(), any())).thenReturn(1);

        // 空白引用：资金定位要素缺失显式拒
        assertThatThrownBy(() -> service.settle(
                        new SettleRequest("S100", List.of(new PaymentLine(PaymentMethod.CARD_BALANCE, 5000L, "  ")))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        // 非数字引用：同码显式拒
        assertThatThrownBy(() -> service.settle(new SettleRequest(
                        "S101", List.of(new PaymentLine(PaymentMethod.CARD_BALANCE, 5000L, "卡9527")))))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        // 引用非法即零资金动作：台账不动卡（锚已抢占但引用解析失败随调用方事务整体回滚）
        verify(cardAccountLedger, never()).record(any());
    }

    @Test
    @DisplayName("按编号查结算单：命中出 VO（枚举转 code、settledAt 出 ISO-8601 文本）")
    void getByNoReturnsVoWhenFound() {
        Settlement st = settlement(900L, "S100", SettlementStatus.SETTLED, 5000L);
        st.setSettledAt(OffsetDateTime.parse("2026-09-17T10:00+08:00"));
        when(settlementMapper.selectOne(any())).thenReturn(st);

        SettlementVO vo = service.getByNo("S100");

        assertThat(vo.settleNo()).isEqualTo("S100");
        assertThat(vo.status()).isEqualTo("SETTLED");
        assertThat(vo.settledAt()).isEqualTo("2026-09-17T10:00+08:00");
        // 查询 SQL 守卫钉死：等值条件落在 settle_no 列且携带请求编号
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<Settlement>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(settlementMapper).selectOne(captor.capture());
        LambdaQueryWrapper<Settlement> wrapper = (LambdaQueryWrapper<Settlement>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("settle_no");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("S100");
    }

    @Test
    @DisplayName("按编号查结算单：无命中 BILL-1014 显式拒（404）")
    void getByNoRejectsMissingAsBill1014() {
        when(settlementMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.getByNo("S404"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.SETTLEMENT_NOT_FOUND));
    }
}
