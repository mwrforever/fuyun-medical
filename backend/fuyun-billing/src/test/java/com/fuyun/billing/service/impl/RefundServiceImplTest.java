package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.dto.RefundApplyRequest;
import com.fuyun.billing.dto.RefundLine;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.entity.RefundFeeLink;
import com.fuyun.billing.entity.RefundRequest;
import com.fuyun.billing.entity.Settlement;
import com.fuyun.billing.enums.ExecOccupyStatus;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.PayerType;
import com.fuyun.billing.enums.RefundStatus;
import com.fuyun.billing.enums.RefundType;
import com.fuyun.billing.enums.SettlementStatus;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.mapper.RefundFeeLinkMapper;
import com.fuyun.billing.mapper.RefundRequestMapper;
import com.fuyun.billing.mapper.SettlementMapper;
import com.fuyun.billing.properties.BillingRefundProperties;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.CardAccountLedger;
import com.fuyun.patient.api.CardTxnRecord;
import com.fuyun.patient.api.CardTxnType;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 退费服务单测（资金红线：执行占用硬前置 BILL-1017、超可退拦截 BILL-1021、双人守卫 BILL-1020、
 * 免审阈值直退与 refund.approved 事件、link 负向台账聚合判态 PART/FULL/REFUNDED 迁移；
 * 分级三级：免审/一级/二级阈值边界、二级审批链落库与连批守卫、二级态驳回）。
 *
 * <p>「票据已开具 → 二级」维度声明：依赖 FU-M13-06 开票记录（明确不在 PR-3 范围），分级判定处为
 * 显式 TODO 占位，本类用例均以金额/医保维度驱动升级——该维度缺省不触发。
 */
@ExtendWith(MockitoExtension.class)
class RefundServiceImplTest {

    /** 门诊就诊号夹具（与原结算行 visit_id 同源） */
    private static final String VISIT = "O2026091700001";

    /** 申请人（登录上下文操作者） */
    private static final String APPLICANT = "cashier-1";

    /** 审批人（双人守卫另一人） */
    private static final String APPROVER = "supervisor-1";

    /** 二级审批人（连批守卫另一人：与申请人/一级审批人三方互异，财务/医保办侧） */
    private static final String SECOND_APPROVER = "finance-1";

    @Mock
    private RefundRequestMapper refundRequestMapper;

    @Mock
    private SettlementMapper settlementMapper;

    @Mock
    private FeeRecordMapper feeRecordMapper;

    @Mock
    private RefundFeeLinkMapper refundFeeLinkMapper;

    @Mock
    private CardAccountLedger cardAccountLedger;

    @Mock
    private ApplicationEventPublisher events;

    private RefundServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RefundRequest.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Settlement.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FeeRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RefundFeeLink.class);
    }

    @BeforeEach
    void setUp() {
        service = new RefundServiceImpl(
                settlementMapper,
                feeRecordMapper,
                refundFeeLinkMapper,
                cardAccountLedger,
                events,
                new ObjectMapper(),
                new BillingRefundProperties(50000L, 200000L));
        ReflectionTestUtils.setField(service, "baseMapper", refundRequestMapper);
        ReflectionTestUtils.setField(service, "entityClass", RefundRequest.class);
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 操作人上下文必须清理，防线程复用串号（与生产过滤器收尾同责）
        OperatorContextHolder.clear();
    }

    private Settlement settlement(long id, long totalAmount) {
        Settlement st = new Settlement();
        st.setId(id);
        st.setSettleNo("S" + id);
        st.setPatientId(7L);
        st.setVisitId(VISIT);
        st.setSettleType(VisitType.OUT);
        st.setPayerType(PayerType.SELF_PAY);
        st.setTotalAmount(totalAmount);
        st.setStatus(SettlementStatus.SETTLED);
        return st;
    }

    private FeeRecord fee(long id, long unitPrice, long amount, LocalDate billingDate, ExecOccupyStatus occupy) {
        FeeRecord fee = new FeeRecord();
        fee.setId(id);
        fee.setVisitId(VISIT);
        fee.setSettlementId(900L);
        fee.setUnitPriceSnapshot(unitPrice);
        fee.setQuantity(BigDecimal.ONE);
        fee.setAmount(amount);
        fee.setBillingDate(billingDate);
        fee.setExecOccupyStatus(occupy);
        fee.setStatus(FeeStatus.SETTLED);
        return fee;
    }

    private RefundRequest refund(long id, RefundStatus status, String applicant, RefundType type, long amount) {
        RefundRequest refund = new RefundRequest();
        refund.setId(id);
        refund.setRefundNo("R" + id);
        refund.setSettlementId(900L);
        refund.setPatientId(7L);
        refund.setVisitId(VISIT);
        refund.setRefundType(type);
        refund.setAmount(amount);
        refund.setReason("退费理由");
        refund.setApplicant(applicant);
        refund.setAutoApproved(false);
        refund.setStatus(status);
        return refund;
    }

    private RefundFeeLink link(long refundId, long feeId, long refundAmount) {
        RefundFeeLink row = new RefundFeeLink();
        row.setId(refundAmount * 10 + feeId);
        row.setRefundId(refundId);
        row.setFeeId(feeId);
        row.setRefundQuantity(BigDecimal.ONE);
        row.setRefundAmount(refundAmount);
        return row;
    }

    /** 打桩：insert 回填雪花 id=100（模拟 MP ASSIGN_ID）+ 历史无 APPROVED/EXECUTED 退费单。 */
    private void stubInsertWithId100AndNoHistory() {
        when(refundRequestMapper.insert(any(RefundRequest.class))).thenAnswer(inv -> {
            inv.getArgument(0, RefundRequest.class).setId(100L);
            return 1;
        });
        when(refundRequestMapper.selectList(any())).thenReturn(List.of());
    }

    /** 打桩：行锁读回目标费用行（并发收口后 apply 守卫唯一取数源，不再逐行 selectById）。 */
    private void stubLockByIdsReturning(FeeRecord... rows) {
        when(feeRecordMapper.lockByIds(any())).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("免审直退：当日更正未占用且阈值内 → APPROVED+auto_approved=true+发 refund.approved(auto=true)")
    void sameDaySmallUnoccupiedRefundAutoApproves() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 3000L));
        stubLockByIdsReturning(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        long id = service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "当日多收费更正"));

        assertThat(id).isEqualTo(100L);
        // 数据库写操作断言：申请单落库（免审直退=落库即 APPROVED，审计免审标识置位）
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(captor.capture());
        RefundRequest row = captor.getValue();
        assertThat(row.getRefundNo()).startsWith("R");
        assertThat(row.getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(row.getAutoApproved()).isTrue();
        assertThat(row.getRefundType()).isEqualTo(RefundType.DAY_CORRECTION);
        assertThat(row.getAmount()).isEqualTo(3000L); // 服务端按明细算额（单价快照×数量），不采信前端
        assertThat(row.getApplicant()).isEqualTo(APPLICANT);
        assertThat(row.getSettlementId()).isEqualTo(900L);
        assertThat(row.getPatientId()).isEqualTo(7L);
        assertThat(row.getVisitId()).isEqualTo(VISIT);
        // link 负向台账落表（退费负向表达唯一载体，禁 fee_record 负向行）
        ArgumentCaptor<RefundFeeLink> linkCaptor = ArgumentCaptor.forClass(RefundFeeLink.class);
        verify(refundFeeLinkMapper).insert(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getRefundId()).isEqualTo(100L);
        assertThat(linkCaptor.getValue().getFeeId()).isEqualTo(1L);
        assertThat(linkCaptor.getValue().getRefundAmount()).isEqualTo(3000L);
        // 免审直退同事件承载（CF-4）：autoApproved=true 为审计抽查检索键
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.refund.approved");
        RefundApprovedPayload payload = (RefundApprovedPayload) published.payload();
        assertThat(payload.refundId()).isEqualTo(100L);
        assertThat(payload.refundNo()).isEqualTo(row.getRefundNo());
        assertThat(payload.settlementId()).isEqualTo(900L);
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.amount()).isEqualTo(3000L);
        assertThat(payload.refundType()).isEqualTo("DAY_CORRECTION");
        assertThat(payload.autoApproved()).isTrue();
        // 可退聚合 SQL 守卫钉死（W-17 双查询）：第 1 次已决集状态谓词落在 status 列且携带
        //   APPROVED/EXECUTED 两态（防漏历史已退）、第 2 次在途集携带 PENDING_APPROVAL/PENDING_SECOND_APPROVAL
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RefundRequest>> statusCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(refundRequestMapper, times(2)).selectList(statusCaptor.capture());
        LambdaQueryWrapper<RefundRequest> statusWrapper =
                (LambdaQueryWrapper<RefundRequest>) statusCaptor.getAllValues().get(0);
        assertThat(statusWrapper.getSqlSegment()).contains("status");
        assertThat(statusWrapper.getParamNameValuePairs().values())
                .contains(RefundStatus.APPROVED, RefundStatus.EXECUTED);
        LambdaQueryWrapper<RefundRequest> inFlightWrapper =
                (LambdaQueryWrapper<RefundRequest>) statusCaptor.getAllValues().get(1);
        // 先取 sqlSegment 触发 IN 参数懒物化（MP formatParam 惰性求值，mock 不渲染 SQL 不会自动回填）
        assertThat(inFlightWrapper.getSqlSegment()).contains("status");
        assertThat(inFlightWrapper.getParamNameValuePairs().values())
                .contains(RefundStatus.PENDING_APPROVAL, RefundStatus.PENDING_SECOND_APPROVAL);
    }

    @Test
    @DisplayName("跨日退费：任一费用行计费日早于今日 → PENDING_APPROVAL 进审批、不自动、不发事件")
    void crossDayRefundRequiresApproval() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 3000L));
        stubLockByIdsReturning(fee(1L, 3000L, 3000L, LocalDate.now().minusDays(1), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "跨日退费"));

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(captor.capture());
        RefundRequest row = captor.getValue();
        assertThat(row.getStatus()).isEqualTo(RefundStatus.PENDING_APPROVAL);
        assertThat(row.getAutoApproved()).isFalse();
        assertThat(row.getRefundType()).isEqualTo(RefundType.CROSS_DAY);
        verifyNoInteractions(events); // 非免审直退不发 refund.approved（事件归 approve 发布点）
    }

    @Test
    @DisplayName("医保结算退费分级：医保 payer 当日小额也判 SETTLED_REFUND 须审批（医保撤销联动前置口径）")
    void insuranceSettledRefundRequiresApproval() {
        OperatorContextHolder.set(APPLICANT);
        Settlement cityIns = settlement(900L, 3000L);
        cityIns.setPayerType(PayerType.CITY_INS);
        when(settlementMapper.selectById(900L)).thenReturn(cityIns);
        stubLockByIdsReturning(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "医保结算退费"));

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(captor.capture());
        assertThat(captor.getValue().getRefundType()).isEqualTo(RefundType.SETTLED_REFUND);
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.PENDING_APPROVAL);
        assertThat(captor.getValue().getAutoApproved()).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("执行占用硬前置：费用行已发药（DISPENSED）→ BILL-1017 拒，申请单与 link 零落库")
    void executeOccupiedFeeBlockedAsBill1017() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 3000L));
        stubLockByIdsReturning(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.DISPENSED));

        assertThatThrownBy(() -> service.apply(
                        new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "已发药误退")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_BLOCKED_BY_EXEC_OCCUPY));
        // 占用即拒：禁「只退钱不退业务」，申请单/link/事件零触碰
        verify(refundRequestMapper, never()).insert(any(RefundRequest.class));
        verify(refundFeeLinkMapper, never()).insert(any(RefundFeeLink.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("超可退拦截：历史已退 2000+本次 2000 > 费用行 3000 → BILL-1021（含历史已退聚合）")
    void refundAmountExceedsRemainingBlocked() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 3000L));
        stubLockByIdsReturning(fee(1L, 1000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));
        // 历史已退：APPROVED 态退费单 101 已退 2000 分
        RefundRequest history = refund(101L, RefundStatus.APPROVED, "cashier-0", RefundType.DAY_CORRECTION, 2000L);
        when(refundRequestMapper.selectList(any())).thenReturn(List.of(history));
        when(refundFeeLinkMapper.selectList(any())).thenReturn(List.of(link(101L, 1L, 2000L)));

        assertThatThrownBy(() -> service.apply(
                        new RefundApplyRequest(900L, List.of(new RefundLine(1L, new BigDecimal("2"))), "超可退")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_AMOUNT_EXCEEDED));
        verify(refundRequestMapper, never()).insert(any(RefundRequest.class));
        verify(refundFeeLinkMapper, never()).insert(any(RefundFeeLink.class));
        // 已退聚合 SQL 守卫钉死（W-17 后已决/在途各求和一次）：link 查询谓词落在 fee_id 列且携带本费用行 id（防跨行串账）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RefundFeeLink>> linkCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(refundFeeLinkMapper, times(2)).selectList(linkCaptor.capture());
        LambdaQueryWrapper<RefundFeeLink> linkWrapper = (LambdaQueryWrapper<RefundFeeLink>) linkCaptor.getValue();
        assertThat(linkWrapper.getSqlSegment()).contains("fee_id");
        assertThat(linkWrapper.getParamNameValuePairs().values()).contains(1L, 101L);
    }

    @Test
    @DisplayName("缺登录上下文：无操作者不可追溯 → BILL-1012 拒（红线 3 退侧同款，资金动作零触碰）")
    void applyRejectsMissingOperatorContextAsBill1012() {
        // 不设置 OperatorContextHolder：模拟无登录上下文请求
        assertThatThrownBy(() ->
                        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "更正")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        verifyNoInteractions(settlementMapper, feeRecordMapper, events);
    }

    @Test
    @DisplayName("缺原结算单：settlementId 无命中 → BILL-1014 拒（404，禁对幽灵结算单退费）")
    void applyRejectsMissingSettlementAsBill1014() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(null);

        assertThatThrownBy(() ->
                        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "更正")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.SETTLEMENT_NOT_FOUND));
        verify(refundRequestMapper, never()).insert(any(RefundRequest.class));
    }

    @Test
    @DisplayName("缺费用行：lockByIds 锁内读回缺行（脏数据）→ BILL-1010 拒（禁静默跳过半截退费）")
    void applyRejectsMissingFeeAsBill1010() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 3000L));
        stubLockByIdsReturning(); // 锁内零行=引用费用不存在

        assertThatThrownBy(() ->
                        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "更正")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.FEE_NOT_FOUND));
        verify(refundRequestMapper, never()).insert(any(RefundRequest.class));
    }

    @Test
    @DisplayName("双人守卫：审批人=申请人 → BILL-1020 拒（等保三级分权），状态与事件零触碰")
    void approveRejectsSelfApprovalAsBill1020() {
        OperatorContextHolder.set(APPLICANT);
        when(refundRequestMapper.selectById(100L))
                .thenReturn(refund(100L, RefundStatus.PENDING_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 3000L));

        assertThatThrownBy(() -> service.approve(100L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_SELF_APPROVAL_FORBIDDEN));
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("审批通过：非自审 → APPROVED 迁移+审批人留痕+发 refund.approved(auto=false)")
    void approvePublishsRefundApprovedEvent() {
        OperatorContextHolder.set(APPROVER);
        RefundRequest pending = refund(100L, RefundStatus.PENDING_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 8000L);
        when(refundRequestMapper.selectById(100L)).thenReturn(pending);

        service.approve(100L);

        // 数据库写操作断言：状态迁移 + 审批人/时刻留痕（同事务）
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(captor.getValue().getApprover()).isEqualTo(APPROVER);
        assertThat(captor.getValue().getApprovedAt()).isNotNull();
        // 事件断言：审批通过事件 autoApproved=false（与免审直退 true 区分，审计抽查检索键）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.refund.approved");
        RefundApprovedPayload payload = (RefundApprovedPayload) published.payload();
        assertThat(payload.refundId()).isEqualTo(100L);
        assertThat(payload.refundNo()).isEqualTo("R100");
        assertThat(payload.settlementId()).isEqualTo(900L);
        assertThat(payload.patientId()).isEqualTo(7L);
        assertThat(payload.amount()).isEqualTo(8000L);
        assertThat(payload.refundType()).isEqualTo("CROSS_DAY");
        assertThat(payload.autoApproved()).isFalse();
    }

    @Test
    @DisplayName("审批缺单：id 无命中 → BILL-1018 拒（404）")
    void approveRejectsMissingRefundAsBill1018() {
        OperatorContextHolder.set(APPROVER);
        when(refundRequestMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.approve(404L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_NOT_FOUND));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("审批状态守卫：非 PENDING_APPROVAL（免审直退行重复批）→ BILL-1019 拒（409）")
    void approveRejectsNonPendingStateAsBill1019() {
        OperatorContextHolder.set(APPROVER);
        when(refundRequestMapper.selectById(100L))
                .thenReturn(refund(100L, RefundStatus.EXECUTED, APPLICANT, RefundType.DAY_CORRECTION, 3000L));

        assertThatThrownBy(() -> service.approve(100L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_STATE_NOT_ALLOWED));
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("驳回：PENDING_APPROVAL → REJECTED 终态并留驳回理由")
    void rejectMovesPendingRefundToRejectedWithReason() {
        OperatorContextHolder.set(APPROVER);
        when(refundRequestMapper.selectById(100L))
                .thenReturn(refund(100L, RefundStatus.PENDING_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 3000L));

        service.reject(100L, "凭证不符，退回补件");

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(captor.getValue().getRejectReason()).isEqualTo("凭证不符，退回补件");
        verifyNoInteractions(events); // 驳回不发 refund.approved（CF-4 仅审批通过承载）
    }

    @Test
    @DisplayName("驳回缺单：id 无命中 → BILL-1018 拒（404）")
    void rejectRejectsMissingRefundAsBill1018() {
        OperatorContextHolder.set(APPROVER);
        when(refundRequestMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.reject(404L, "理由"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_NOT_FOUND));
    }

    @Test
    @DisplayName("驳回状态守卫：非 PENDING_APPROVAL（已执行单不可驳回）→ BILL-1019 拒（409）")
    void rejectRejectsNonPendingStateAsBill1019() {
        OperatorContextHolder.set(APPROVER);
        when(refundRequestMapper.selectById(100L))
                .thenReturn(refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 3000L));

        assertThatThrownBy(() -> service.reject(100L, "理由"))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_STATE_NOT_ALLOWED));
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
    }

    @Test
    @DisplayName("免审边界：当日更正金额恰等于 autoExemptFen → 仍免审直退（≤ 阈值判免审）")
    void sameDayAmountAtExemptThresholdStillAutoApproves() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 50000L));
        stubLockByIdsReturning(fee(1L, 50000L, 50000L, LocalDate.now(), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "恰好等于免审阈值"));

        // 边界钉死：恰等于 autoExemptFen（50000 分）归免审（「≤ 阈值」口径），落库即 APPROVED
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(captor.getValue().getAutoApproved()).isTrue();
    }

    @Test
    @DisplayName("一级即终批：跨日自费 60000 分（超免审未超一级上限）→ 一级批后 APPROVED+发事件")
    void crossDayAmountWithinSingleApprovalFinishesAtFirstLevel() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 60000L));
        stubLockByIdsReturning(fee(1L, 60000L, 60000L, LocalDate.now().minusDays(1), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "跨日自费超免审"));
        ArgumentCaptor<RefundRequest> applyCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(applyCaptor.capture());
        RefundRequest row = applyCaptor.getValue();
        assertThat(row.getStatus()).isEqualTo(RefundStatus.PENDING_APPROVAL); // L1 亦先落待一审（唯一审批入口）
        assertThat(row.getRefundType()).isEqualTo(RefundType.CROSS_DAY);
        assertThat(row.getAutoApproved()).isFalse();

        when(refundRequestMapper.selectById(100L)).thenReturn(row);
        OperatorContextHolder.set(APPROVER);
        service.approve(100L);

        // 一级即终批：APPROVED + 终批人/时刻留痕；非二级单不落一级审批链（firstApprover 保持空）
        ArgumentCaptor<RefundRequest> approveCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(approveCaptor.capture());
        assertThat(approveCaptor.getValue().getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(approveCaptor.getValue().getApprover()).isEqualTo(APPROVER);
        assertThat(approveCaptor.getValue().getApprovedAt()).isNotNull();
        assertThat(approveCaptor.getValue().getFirstApprover()).isNull();
        verify(events).publishEvent(any(BillingDomainEvent.class));
    }

    @Test
    @DisplayName("一级边界钉死：金额恰等于 singleApprovalFen → 归一级（一级批即 APPROVED，非二级升批）")
    void amountAtSingleApprovalThresholdStaysFirstLevel() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 200000L));
        stubLockByIdsReturning(fee(1L, 200000L, 200000L, LocalDate.now().minusDays(1), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "恰等于一级上限"));
        ArgumentCaptor<RefundRequest> applyCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(applyCaptor.capture());
        RefundRequest row = applyCaptor.getValue();

        when(refundRequestMapper.selectById(100L)).thenReturn(row);
        OperatorContextHolder.set(APPROVER);
        service.approve(100L);

        // 严格大于才升二级（等于归一级）：一级批即终批 APPROVED，禁入 PENDING_SECOND_APPROVAL
        ArgumentCaptor<RefundRequest> approveCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(approveCaptor.capture());
        assertThat(approveCaptor.getValue().getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(approveCaptor.getValue().getFirstApprover()).isNull();
    }

    @Test
    @DisplayName("二级升批：金额超 singleApprovalFen → 一级批转 PENDING_SECOND_APPROVAL+审批链落库+零事件")
    void amountAboveSingleApprovalThresholdEscalatesToSecondApproval() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 200001L));
        stubLockByIdsReturning(fee(1L, 200001L, 200001L, LocalDate.now().minusDays(1), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "超一级上限大额"));
        ArgumentCaptor<RefundRequest> applyCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(applyCaptor.capture());
        RefundRequest row = applyCaptor.getValue();
        assertThat(row.getStatus()).isEqualTo(RefundStatus.PENDING_APPROVAL); // L2 亦从待一审起（级别在批时判定）

        when(refundRequestMapper.selectById(100L)).thenReturn(row);
        OperatorContextHolder.set(APPROVER);
        service.approve(100L);

        // 一级审批（L2）：状态推进待二级 + 一级审批人/时刻落库；终批人保持空、零事件（事件时点=终批）
        ArgumentCaptor<RefundRequest> approveCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(approveCaptor.capture());
        RefundRequest first = approveCaptor.getValue();
        assertThat(first.getStatus()).isEqualTo(RefundStatus.PENDING_SECOND_APPROVAL);
        assertThat(first.getFirstApprover()).isEqualTo(APPROVER);
        assertThat(first.getFirstApprovedAt()).isNotNull();
        assertThat(first.getApprover()).isNull();
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("医保直判二级：payerType≠SELF_PAY 当日小额也升二级（基金已支出退费升审）")
    void insurancePayerEscalatesToSecondApproval() {
        OperatorContextHolder.set(APPLICANT);
        Settlement cityIns = settlement(900L, 3000L);
        cityIns.setPayerType(PayerType.CITY_INS);
        when(settlementMapper.selectById(900L)).thenReturn(cityIns);
        stubLockByIdsReturning(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "医保结算退款"));
        ArgumentCaptor<RefundRequest> applyCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).insert(applyCaptor.capture());
        RefundRequest row = applyCaptor.getValue();
        // 现口径解耦复用：医保已结算 → SETTLED_REFUND，分级判定据其直判 L2（当日小额亦不例外）
        assertThat(row.getRefundType()).isEqualTo(RefundType.SETTLED_REFUND);
        assertThat(row.getStatus()).isEqualTo(RefundStatus.PENDING_APPROVAL);
        assertThat(row.getAutoApproved()).isFalse();

        when(refundRequestMapper.selectById(100L)).thenReturn(row);
        OperatorContextHolder.set(APPROVER);
        service.approve(100L);

        ArgumentCaptor<RefundRequest> approveCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(approveCaptor.capture());
        assertThat(approveCaptor.getValue().getStatus()).isEqualTo(RefundStatus.PENDING_SECOND_APPROVAL);
        assertThat(approveCaptor.getValue().getFirstApprover()).isEqualTo(APPROVER);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("二级终批：待二级单二级批 → APPROVED+终批人留痕+发 refund.approved(auto=false)")
    void secondLevelApprovalFinalizesAndPublishesEvent() {
        OperatorContextHolder.set(SECOND_APPROVER);
        RefundRequest pending =
                refund(100L, RefundStatus.PENDING_SECOND_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 200001L);
        pending.setFirstApprover(APPROVER);
        when(refundRequestMapper.selectById(100L)).thenReturn(pending);

        service.approve(100L);

        // 终批落态：APPROVED + 终批人/时刻（approver/approvedAt），一级链留痕原样保留（审计链完整）
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(captor.getValue().getApprover()).isEqualTo(SECOND_APPROVER);
        assertThat(captor.getValue().getApprovedAt()).isNotNull();
        assertThat(captor.getValue().getFirstApprover()).isEqualTo(APPROVER);
        // 事件时点=终批：载荷与免审/一级批同构（CF-4 冻结），autoApproved=false 区分免审直退
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.refund.approved");
        RefundApprovedPayload payload = (RefundApprovedPayload) published.payload();
        assertThat(payload.refundId()).isEqualTo(100L);
        assertThat(payload.amount()).isEqualTo(200001L);
        assertThat(payload.refundType()).isEqualTo("CROSS_DAY");
        assertThat(payload.autoApproved()).isFalse();
    }

    @Test
    @DisplayName("连批守卫：二级审批人=一级审批人 → BILL-1020 拒（同一账号不得连批两级），状态与事件零触碰")
    void secondApprovalByFirstApproverRejectedAsBill1020() {
        OperatorContextHolder.set(APPROVER);
        RefundRequest pending =
                refund(100L, RefundStatus.PENDING_SECOND_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 200001L);
        pending.setFirstApprover(APPROVER);
        when(refundRequestMapper.selectById(100L)).thenReturn(pending);

        assertThatThrownBy(() -> service.approve(100L)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.REFUND_SELF_APPROVAL_FORBIDDEN);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        });
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("二级态驳回：PENDING_SECOND_APPROVAL → REJECTED 终态留痕（一级已批后财务/医保办否决整单）")
    void rejectSecondLevelPendingRefundMovesToRejected() {
        OperatorContextHolder.set(SECOND_APPROVER);
        RefundRequest pending =
                refund(100L, RefundStatus.PENDING_SECOND_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 200001L);
        pending.setFirstApprover(APPROVER);
        when(refundRequestMapper.selectById(100L)).thenReturn(pending);

        service.reject(100L, "大额退费凭证不符，整单退回");

        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RefundStatus.REJECTED);
        assertThat(captor.getValue().getRejectReason()).isEqualTo("大额退费凭证不符，整单退回");
        verifyNoInteractions(events); // 驳回不发 refund.approved（CF-4 仅审批通过承载）
    }

    @Test
    @DisplayName("执行退回：CARD_BALANCE 行台账 REFUND 入账回填流水、费用 FULL_REFUND、结算单转 REFUNDED")
    void executeRefundsCardBalanceAndMarksSettlementRefunded() {
        RefundRequest approved = refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 3000L);
        // W-16 后 execute 首步即 CAS 抢锚：缺此桩则 CAS 默认 0 行→重读仍 APPROVED→拒 BILL-1019
        when(refundRequestMapper.casMarkExecuted(100L)).thenReturn(1);
        when(refundRequestMapper.selectById(100L)).thenReturn(approved);
        Settlement st = settlement(900L, 3000L);
        // 纯卡支付夹具（W-16 F6 守卫后正路径要求退额 ≤ 卡侧原付合计；CASH 混付形态解析覆盖
        //   由 executeAggregatesSameCardRowsIntoSingleCredit 承载）
        st.setPaymentDetails("[{\"method\":\"CARD_BALANCE\",\"amount\":3000,\"channelRef\":\"5\"}]");
        when(settlementMapper.selectById(900L)).thenReturn(st);
        // 同一 mapper 多处查询（本单 link 清单 / refundedFen 已决+在途 / 结算维度聚合）打桩同值：本单已退=3000 分
        when(refundFeeLinkMapper.selectList(any())).thenReturn(List.of(link(100L, 1L, 3000L)));
        when(refundRequestMapper.selectList(any())).thenReturn(List.of(approved));
        when(feeRecordMapper.selectById(1L)).thenReturn(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));
        when(cardAccountLedger.record(any())).thenReturn(777L);

        service.execute(100L);

        // 原路退回·就诊卡侧：channelRef=5 → 台账 REFUND 入账恰一次，金额=退费额、对账键=refundNo
        verify(cardAccountLedger, times(1)).record(new CardTxnRecord(5L, CardTxnType.REFUND, 3000L, "R100"));
        // 退费单终态：EXECUTED + 台账流水 id 回填（资金溯源锚）
        ArgumentCaptor<RefundRequest> refundCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.EXECUTED);
        assertThat(refundCaptor.getValue().getPaymentRefundRef()).isEqualTo("777");
        // 费用行判态：「既有已退+本次退」≥ 行金额 → FULL_REFUND
        ArgumentCaptor<FeeRecord> feeCaptor = ArgumentCaptor.forClass(FeeRecord.class);
        verify(feeRecordMapper).updateById(feeCaptor.capture());
        assertThat(feeCaptor.getValue().getStatus()).isEqualTo(FeeStatus.FULL_REFUND);
        // 结算单全额退完 → REFUNDED
        ArgumentCaptor<Settlement> stCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).updateById(stCaptor.capture());
        assertThat(stCaptor.getValue().getStatus()).isEqualTo(SettlementStatus.REFUNDED);
        // link 聚合 SQL 守卫钉死：本单 link 清单谓词落在 refund_id 列且携带本单 id
        //  （W-17 后共 4 次：清单 1 + refundedFen 已决/在途集内求和 2 + 结算维度求和 1）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RefundFeeLink>> linkCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(refundFeeLinkMapper, times(4)).selectList(linkCaptor.capture());
        LambdaQueryWrapper<RefundFeeLink> loopWrapper =
                (LambdaQueryWrapper<RefundFeeLink>) linkCaptor.getAllValues().get(0);
        assertThat(loopWrapper.getSqlSegment()).contains("refund_id");
        assertThat(loopWrapper.getParamNameValuePairs().values()).contains(100L);
        // 已退聚合状态谓词守卫：第 1 次已决集 APPROVED/EXECUTED 两态（本单判定时点 APPROVED 必须计入，剔除即永判不满）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RefundRequest>> statusCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(refundRequestMapper, times(3)).selectList(statusCaptor.capture());
        LambdaQueryWrapper<RefundRequest> statusWrapper =
                (LambdaQueryWrapper<RefundRequest>) statusCaptor.getAllValues().get(0);
        assertThat(statusWrapper.getSqlSegment()).contains("status");
        assertThat(statusWrapper.getParamNameValuePairs().values())
                .contains(RefundStatus.APPROVED, RefundStatus.EXECUTED);
        // 结算单聚合谓词守卫：第 3 次（已决/在途集之后）settlement_id 等值（防跨结算单串账）
        LambdaQueryWrapper<RefundRequest> settleWrapper =
                (LambdaQueryWrapper<RefundRequest>) statusCaptor.getAllValues().get(2);
        assertThat(settleWrapper.getSqlSegment()).contains("settlement_id");
        assertThat(settleWrapper.getParamNameValuePairs().values()).contains(900L);
    }

    @Test
    @DisplayName("同卡多行聚合入账：拆分卡支付两行 CARD_BALANCE 同 channelRef → 台账 REFUND 恰一次全额贷记")
    void executeAggregatesSameCardRowsIntoSingleCredit() {
        RefundRequest approved = refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 5000L);
        // W-16 后 execute 首步即 CAS 抢锚：缺此桩则 CAS 默认 0 行→重读仍 APPROVED→拒 BILL-1019
        when(refundRequestMapper.casMarkExecuted(100L)).thenReturn(1);
        when(refundRequestMapper.selectById(100L)).thenReturn(approved);
        Settlement st = settlement(900L, 5000L);
        // 同卡拆分两行（写入侧同卡多行求和扣款合形态）：2000+3000 同 channelRef="5"
        st.setPaymentDetails("[{\"method\":\"CASH\",\"amount\":1000,\"channelRef\":null},"
                + "{\"method\":\"CARD_BALANCE\",\"amount\":2000,\"channelRef\":\"5\"},"
                + "{\"method\":\"CARD_BALANCE\",\"amount\":3000,\"channelRef\":\"5\"}]");
        when(settlementMapper.selectById(900L)).thenReturn(st);
        when(refundFeeLinkMapper.selectList(any())).thenReturn(List.of(link(100L, 1L, 5000L)));
        when(refundRequestMapper.selectList(any())).thenReturn(List.of(approved));
        when(feeRecordMapper.selectById(1L)).thenReturn(fee(1L, 5000L, 5000L, LocalDate.now(), ExecOccupyStatus.NONE));
        when(cardAccountLedger.record(any())).thenReturn(777L);

        service.execute(100L);

        // 并发收口（2026-09-18 裁决）：同卡多行按 channelRef 聚合单次全额贷记——修复前逐行全额
        //   贷记会入账两倍退费额；断言台账恰一次且金额=退费额 5000（与写入侧求和扣款口径对称）
        verify(cardAccountLedger, times(1)).record(new CardTxnRecord(5L, CardTxnType.REFUND, 5000L, "R100"));
        ArgumentCaptor<RefundRequest> refundCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getPaymentRefundRef()).isEqualTo("777");
    }

    @Test
    @DisplayName("行锁守卫顺序：apply 先锁目标费用行（FOR UPDATE 串行化并发申请）后落申请单，锁参数=费用行 id 集")
    void applyLocksFeeRowsBeforePersistingRequest() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(900L)).thenReturn(settlement(900L, 3000L));
        stubLockByIdsReturning(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));
        stubInsertWithId100AndNoHistory();

        service.apply(new RefundApplyRequest(900L, List.of(new RefundLine(1L, BigDecimal.ONE)), "更正"));

        // 锁先于守卫与落库：并发双申请同费用行时后到者在 lockByIds 阻塞至先到者提交，
        //   锁内重读的已退聚合含先到申请 → 超可退守卫即拒（TOCTOU 根除锚点，顺序不可倒置）
        InOrder order = inOrder(feeRecordMapper, refundRequestMapper);
        order.verify(feeRecordMapper).lockByIds(List.of(1L));
        order.verify(refundRequestMapper).insert(any(RefundRequest.class));
    }

    @Test
    @DisplayName("部分退：已退合计 < 结算总额 → 费用行 PART/FULL 按行判态、结算单留 SETTLED、纯现金不触台账")
    void executeKeepsSettlementSettledOnPartialRefund() {
        RefundRequest approved = refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 3000L);
        // W-16 后 execute 首步即 CAS 抢锚：缺此桩则 CAS 默认 0 行→重读仍 APPROVED→拒 BILL-1019
        when(refundRequestMapper.casMarkExecuted(100L)).thenReturn(1);
        when(refundRequestMapper.selectById(100L)).thenReturn(approved);
        Settlement st = settlement(900L, 8000L);
        st.setPaymentDetails("[{\"method\":\"CASH\",\"amount\":8000,\"channelRef\":null}]");
        when(settlementMapper.selectById(900L)).thenReturn(st);
        // 本单仅退费用行 1（3000 分）
        when(refundFeeLinkMapper.selectList(any())).thenReturn(List.of(link(100L, 1L, 3000L)));
        when(refundRequestMapper.selectList(any())).thenReturn(List.of(approved));
        when(feeRecordMapper.selectById(1L)).thenReturn(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));

        service.execute(100L);

        // 纯 CASH 支付：payment_details 无 CARD_BALANCE 行 → 台账零触碰、退费单无原路流水
        verifyNoInteractions(cardAccountLedger);
        ArgumentCaptor<RefundRequest> refundCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.EXECUTED);
        assertThat(refundCaptor.getValue().getPaymentRefundRef()).isNull();
        // 费用行 1 全退
        ArgumentCaptor<FeeRecord> feeCaptor = ArgumentCaptor.forClass(FeeRecord.class);
        verify(feeRecordMapper).updateById(feeCaptor.capture());
        assertThat(feeCaptor.getValue().getStatus()).isEqualTo(FeeStatus.FULL_REFUND);
        // 已退 3000 < 结算总额 8000 → 结算单留 SETTLED（部分退不改结算终态）
        verify(settlementMapper, never()).updateById(any(Settlement.class));
    }

    @Test
    @DisplayName("结算聚合空集兜底：结算维度已退集为空（防御分支）→ 0 < 总额留 SETTLED、退费单照常执行")
    void executeKeepsSettlementSettledWhenSettlementAggregateEmpty() {
        RefundRequest approved = refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 3000L);
        // W-16 后 execute 首步即 CAS 抢锚：缺此桩则 CAS 默认 0 行→重读仍 APPROVED→拒 BILL-1019
        when(refundRequestMapper.casMarkExecuted(100L)).thenReturn(1);
        when(refundRequestMapper.selectById(100L)).thenReturn(approved);
        Settlement st = settlement(900L, 8000L);
        st.setPaymentDetails("[{\"method\":\"CASH\",\"amount\":8000,\"channelRef\":null}]");
        when(settlementMapper.selectById(900L)).thenReturn(st);
        when(refundFeeLinkMapper.selectList(any())).thenReturn(List.of(link(100L, 1L, 3000L)));
        // 第 1/2 次查询（refundedFen 已决/在途）首次命中本单、在途空集；第 3 次（totalRefundedFen 结算维度）空集 → 聚合 0 分
        when(refundRequestMapper.selectList(any())).thenReturn(List.of(approved), List.of());
        when(feeRecordMapper.selectById(1L)).thenReturn(fee(1L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE));

        service.execute(100L);

        // 已退聚合 0 < 结算总额 8000 → 结算单留 SETTLED（部分退不改结算终态）
        verify(settlementMapper, never()).updateById(any(Settlement.class));
        ArgumentCaptor<RefundRequest> refundCaptor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(refundRequestMapper).updateById(refundCaptor.capture());
        assertThat(refundCaptor.getValue().getStatus()).isEqualTo(RefundStatus.EXECUTED);
    }

    @Test
    @DisplayName("执行读回卡引用守卫：channelRef JSON null/空文本/非数字/缺键均 BILL-1012 拒（400，禁裸 parseLong 出 500）")
    void executeRejectsMissingOrIllegalChannelRefAsBill1012() {
        // W-16 后 execute 首步即 CAS 抢锚：缺此桩则 CAS 默认 0 行→重读仍 APPROVED→拒 BILL-1019
        when(refundRequestMapper.casMarkExecuted(100L)).thenReturn(1);
        when(refundRequestMapper.selectById(100L))
                .thenReturn(refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 3000L));
        Settlement st = settlement(900L, 3000L);
        when(settlementMapper.selectById(900L)).thenReturn(st);
        // 四类非法落库形态：JSON null（写入侧三键保形产物）/ 空文本 / 非数字 / channelRef 键缺失
        List<String> illegalDetails = List.of(
                "[{\"method\":\"CARD_BALANCE\",\"amount\":2000,\"channelRef\":null}]",
                "[{\"method\":\"CARD_BALANCE\",\"amount\":2000,\"channelRef\":\"  \"}]",
                "[{\"method\":\"CARD_BALANCE\",\"amount\":2000,\"channelRef\":\"卡9527\"}]",
                "[{\"method\":\"CARD_BALANCE\",\"amount\":2000}]");

        for (String details : illegalDetails) {
            st.setPaymentDetails(details);
            assertThatThrownBy(() -> service.execute(100L))
                    .as("非法卡引用形态应显式拒：%s", details)
                    .isInstanceOfSatisfying(BizException.class, e -> {
                        // 契约内形态：4xx + BILL-1012（修复前裸 parseLong 抛 NumberFormatException 经兜底渲染成 500）
                        assertThat(e.getErrorCode()).isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING);
                        assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    });
        }
        // 引用非法即零资金动作：台账不入账、退费单不迁移、结算单不改态
        verifyNoInteractions(cardAccountLedger);
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
        verify(settlementMapper, never()).updateById(any(Settlement.class));
    }

    @Test
    @DisplayName("支付明细解析失败：落库文本破损 → IllegalStateException 显式暴露（禁静默跳过退回）")
    void executeRejectsCorruptPaymentDetailsAsIllegalState() {
        RefundRequest approved = refund(100L, RefundStatus.APPROVED, APPLICANT, RefundType.DAY_CORRECTION, 3000L);
        // W-16 后 execute 首步即 CAS 抢锚：缺此桩则 CAS 默认 0 行→重读仍 APPROVED→拒 BILL-1019
        when(refundRequestMapper.casMarkExecuted(100L)).thenReturn(1);
        when(refundRequestMapper.selectById(100L)).thenReturn(approved);
        Settlement st = settlement(900L, 3000L);
        st.setPaymentDetails("broken-json");
        when(settlementMapper.selectById(900L)).thenReturn(st);

        assertThatThrownBy(() -> service.execute(100L)).isInstanceOf(IllegalStateException.class);
        // 数据不一致即停：不退钱、不改态（事务回滚由调用方语义保证）
        verifyNoInteractions(cardAccountLedger);
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
        verify(settlementMapper, never()).updateById(any(Settlement.class));
    }

    @Test
    @DisplayName("执行状态守卫：非 APPROVED（待审批单不可执行）→ BILL-1019 拒（409），资金零触碰")
    void executeRejectsNonApprovedStateAsBill1019() {
        when(refundRequestMapper.selectById(100L))
                .thenReturn(refund(100L, RefundStatus.PENDING_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 3000L));

        assertThatThrownBy(() -> service.execute(100L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_STATE_NOT_ALLOWED));
        verifyNoInteractions(settlementMapper, cardAccountLedger, events);
        verify(refundRequestMapper, never()).updateById(any(RefundRequest.class));
    }

    @Test
    @DisplayName("执行缺单：id 无命中 → BILL-1018 拒（404）")
    void executeRejectsMissingRefundAsBill1018() {
        when(refundRequestMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.execute(404L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_NOT_FOUND));
    }

    @Test
    @DisplayName("退费分页查询：status 可空=全部、id 升序稳定排序（wrapper 守卫钉死）")
    void pageReturnsRefundsWithOptionalStatusFilter() {
        RefundRequest row = refund(100L, RefundStatus.PENDING_APPROVAL, APPLICANT, RefundType.CROSS_DAY, 3000L);
        when(refundRequestMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<RefundRequest> page = inv.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1);
            return page;
        });

        PageResult<RefundRequest> result = service.page(RefundStatus.PENDING_APPROVAL, 0, 20);

        assertThat(result.page()).isZero(); // 0 基分页契约原样回显
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.content()).hasSize(1);
        // 查询 SQL 守卫钉死：等值条件落在 status 列且携带请求状态、排序为 id 升序（A.4.3-17）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RefundRequest>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(refundRequestMapper).selectPage(any(), wrapperCaptor.capture());
        LambdaQueryWrapper<RefundRequest> wrapper = (LambdaQueryWrapper<RefundRequest>) wrapperCaptor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("status").containsIgnoringCase("ORDER BY");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(RefundStatus.PENDING_APPROVAL);
    }

    // ===== W-16：execute CAS 锚与卡侧守卫 =====

    @Test
    @DisplayName("W-16 并发输家幂等：CAS 0 行重读已 EXECUTED 直返（零资金动作零异常）")
    void executeConcurrentLoserReturnsIdempotentlyWhenAlreadyExecuted() {
        RefundRequest executed = refund(7L, RefundStatus.EXECUTED, "cashier-0", RefundType.DAY_CORRECTION, 3000L);
        when(refundRequestMapper.casMarkExecuted(7L)).thenReturn(0);
        when(refundRequestMapper.selectById(7L)).thenReturn(executed);

        service.execute(7L);

        verify(cardAccountLedger, never()).record(any());
    }

    // 「CAS 后缺行 BILL-1018」不另设用例（复审 N4 去重）：既有 executeRejectsMissingRefundAsBill1018
    // 在本改造后自然命中同路径——execute 首步 casMarkExecuted 未命中（mock 缺省 0 行）→重读 null→
    // BILL-1018，原打桩（selectById 404L→null）零改动直过，同路径禁双用例。

    @Test
    @DisplayName("W-16 并发输家仍非终态：拒 BILL-1019（禁带 APPROVED 继续资金动作）")
    void executeConcurrentLoserRejectsAsBill1019WhenStillApproved() {
        when(refundRequestMapper.casMarkExecuted(7L)).thenReturn(0);
        when(refundRequestMapper.selectById(7L))
                .thenReturn(refund(7L, RefundStatus.APPROVED, "cashier-0", RefundType.DAY_CORRECTION, 3000L));

        assertThatThrownBy(() -> service.execute(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_STATE_NOT_ALLOWED));
        verify(cardAccountLedger, never()).record(any());
    }

    @Test
    @DisplayName("W-16 卡行金额非法：channelRef 聚合后单卡原付 ≤0 拒 BILL-1029（防负行凭空入卡）")
    void executeRejectsNonPositiveCardChannelAsBill1029() {
        RefundRequest approved = refund(7L, RefundStatus.APPROVED, "cashier-0", RefundType.DAY_CORRECTION, 3000L);
        when(refundRequestMapper.casMarkExecuted(7L)).thenReturn(1);
        when(refundRequestMapper.selectById(7L)).thenReturn(approved);
        Settlement st = settlement(900L, 3000L);
        st.setPaymentDetails("[{\"method\":\"CARD_BALANCE\",\"amount\":-500,\"channelRef\":\"9\"}]");
        when(settlementMapper.selectById(900L)).thenReturn(st);

        assertThatThrownBy(() -> service.execute(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_CARD_CHANNEL_INVALID));
        verify(cardAccountLedger, never()).record(any());
    }

    @Test
    @DisplayName("W-16 退款额超卡侧原付合计：拒 BILL-1029（F6 加固——退额以卡侧实付为上限）")
    void executeRejectsRefundExceedingCardOriginalPaymentAsBill1029() {
        // 退费额 8000 > 卡侧原付合计 3000（混付单卡侧份额伪造放大面封堵）
        RefundRequest approved = refund(7L, RefundStatus.APPROVED, "cashier-0", RefundType.DAY_CORRECTION, 8000L);
        when(refundRequestMapper.casMarkExecuted(7L)).thenReturn(1);
        when(refundRequestMapper.selectById(7L)).thenReturn(approved);
        Settlement st = settlement(900L, 3000L);
        st.setPaymentDetails("[{\"method\":\"CARD_BALANCE\",\"amount\":1000,\"channelRef\":\"9\"},"
                + "{\"method\":\"CARD_BALANCE\",\"amount\":2000,\"channelRef\":\"9\"}]");
        when(settlementMapper.selectById(900L)).thenReturn(st);

        assertThatThrownBy(() -> service.execute(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_CARD_CHANNEL_INVALID));
        verify(cardAccountLedger, never()).record(any());
    }

    // ===== W-17：在途额度口径 =====

    @Test
    @DisplayName("W-17 在途聚合：已决全量 + 在途除自身（excludeInFlightRefundId 语义锁定）")
    void refundedFenCountsDecidedFullyAndInFlightExcludingSelf() {
        // 打桩=模拟 SQL 结果：第一次聚合查已决（APPROVED/EXECUTED）→ 仅 [11]；
        // 第二次查在途（PENDING_* 且 .ne(12) 排除自身在 SQL 侧生效）→ 仅 [13]，单 12 不得出现于返回集
        when(refundRequestMapper.selectList(any()))
                .thenReturn(
                        List.of(refund(11L, RefundStatus.APPROVED, "cashier-0", RefundType.DAY_CORRECTION, 300L)),
                        List.of(refund(
                                13L,
                                RefundStatus.PENDING_SECOND_APPROVAL,
                                "cashier-0",
                                RefundType.DAY_CORRECTION,
                                100L)));
        // link 求和随两次集内查询依序打桩：已决集 [11]→300 分；在途集 [13]→100 分
        when(refundFeeLinkMapper.selectList(any()))
                .thenReturn(List.of(link(11L, 9L, 300L)), List.of(link(13L, 9L, 100L)));

        long occupied = service.refundedFen(9L, 12L); // 包级双参直调，无别名方法

        assertThat(occupied).isEqualTo(400L); // 300（已决）+100（他单在途）；自身单 12 被 .ne(12) 排除不入集
        verify(refundRequestMapper, times(2)).selectList(any()); // 已决/在途各聚合恰一次
        verify(refundFeeLinkMapper, times(2)).selectList(any());
    }

    // ===== W-18：apply 双守卫 =====

    @Test
    @DisplayName("W-18 结算单状态守卫：DRAFT 结算单退费申请拒 BILL-1030（SETTLED 唯一可退基点，封堵免审绕过主链）")
    void applyRejectsDraftSettlementAsBill1030() {
        OperatorContextHolder.set(APPLICANT);
        Settlement draft = settlement(5L, 3000L);
        draft.setStatus(SettlementStatus.DRAFT);
        when(settlementMapper.selectById(5L)).thenReturn(draft);

        assertThatThrownBy(() -> service.apply(
                        new RefundApplyRequest(5L, List.of(new RefundLine(9L, BigDecimal.ONE)), "当日多收费更正")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_SETTLEMENT_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("W-18 费用行归属守卫：settlementId 与原单不符拒 BILL-1031")
    void applyRejectsFeeNotInSettlementAsBill1031() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(5L)).thenReturn(settlement(5L, 3000L));
        FeeRecord foreign = fee(9L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE);
        foreign.setSettlementId(99L); // 归属结算单 99 ≠ 5
        when(feeRecordMapper.lockByIds(any())).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.apply(
                        new RefundApplyRequest(5L, List.of(new RefundLine(9L, BigDecimal.ONE)), "当日多收费更正")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.REFUND_FEE_NOT_IN_SETTLEMENT));
    }

    @Test
    @DisplayName("W-18 费用行状态守卫：PENDING 行不可退（复用 BILL-1011 不新增码位）")
    void applyRejectsRefundablePendingFeeAsBill1011() {
        OperatorContextHolder.set(APPLICANT);
        when(settlementMapper.selectById(5L)).thenReturn(settlement(5L, 3000L));
        FeeRecord pending = fee(9L, 3000L, 3000L, LocalDate.now(), ExecOccupyStatus.NONE);
        pending.setStatus(FeeStatus.PENDING);
        pending.setSettlementId(5L); // 归属对齐原结算单（复审 N3）：W-18 归属守卫在前，不对齐会先命中 BILL-1031 而非本用例期望的行状态 BILL-1011
        when(feeRecordMapper.lockByIds(any())).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.apply(
                        new RefundApplyRequest(5L, List.of(new RefundLine(9L, BigDecimal.ONE)), "当日多收费更正")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.FEE_STATE_NOT_ALLOWED));
    }
}
