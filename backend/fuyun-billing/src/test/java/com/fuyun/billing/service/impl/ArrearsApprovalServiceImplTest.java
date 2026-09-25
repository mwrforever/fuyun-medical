package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.ArrearsApprovedPayload;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.ArrearsApprovalCreateRequest;
import com.fuyun.billing.entity.ArrearsApproval;
import com.fuyun.billing.entity.DepositAccount;
import com.fuyun.billing.enums.ArrearsApprovalStatus;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.ArrearsApprovalMapper;
import com.fuyun.billing.mapper.DepositAccountMapper;
import com.fuyun.billing.vo.ArrearsApprovalVO;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
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

/**
 * 挂账审批服务单测（P2 PR-1 Task 13，brief 冻结用例⑧⑨承载面）：审批通过（CAS+押金余额快照+
 * arrears.approved 事件载荷四字段逐断言）/驳回保持（终态不发事件）/BILL-1032/1033/1012 守卫。
 * GC22 操作者上下文经 OperatorContextHolder 注入（禁前端传人）。
 */
@ExtendWith(MockitoExtension.class)
class ArrearsApprovalServiceImplTest {

    private static final String NO = "AR2026092500001";

    private static final String VISIT = "I20260925000001";

    @Mock
    private ArrearsApprovalMapper arrearsApprovalMapper;

    @Mock
    private DepositAccountMapper depositAccountMapper;

    @Mock
    private ApplicationEventPublisher events;

    private ArrearsApprovalServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ArrearsApproval.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DepositAccount.class);
    }

    @BeforeEach
    void setUp() {
        service = new ArrearsApprovalServiceImpl(arrearsApprovalMapper, depositAccountMapper, events);
        // GC22：审批操作者恒取登录上下文（禁前端传人）
        OperatorContextHolder.set("EMP-001");
    }

    @AfterEach
    void tearDown() {
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("创建挂账审批单：单号服务端生成 + 落库即 PENDING_APPROVAL（DRAFT 内联瞬时态）")
    void createPersistsPendingApproval() {
        ArrearsApprovalVO vo = service.create(new ArrearsApprovalCreateRequest(VISIT, "押金不足暂欠"));

        assertThat(vo.approvalNo()).startsWith("AR");
        assertThat(vo.visitId()).isEqualTo(VISIT);
        assertThat(vo.applyReason()).isEqualTo("押金不足暂欠");
        assertThat(vo.status()).isEqualTo(ArrearsApprovalStatus.PENDING_APPROVAL.getCode());
        ArgumentCaptor<ArrearsApproval> captor = ArgumentCaptor.forClass(ArrearsApproval.class);
        verify(arrearsApprovalMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ArrearsApprovalStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("⑧ 审批通过：CAS 决出+押金余额快照，arrears.approved 事件载荷四字段逐字（visitId/approvalNo/approvedAt/approvedBalance）")
    void approveCascadesDecisionAndPublishesEventWithFourFields() {
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(pendingApproval());
        when(depositAccountMapper.selectOne(any(Wrapper.class))).thenReturn(account(8000L));
        when(arrearsApprovalMapper.casDecide(eq(NO), eq("APPROVED"), eq("EMP-001"), eq(8000L)))
                .thenReturn(1);

        ArrearsApprovalVO vo = service.approve(NO);

        assertThat(vo.status()).isEqualTo(ArrearsApprovalStatus.APPROVED.getCode());
        assertThat(vo.approver()).isEqualTo("EMP-001");
        assertThat(vo.approvedBalance()).isEqualTo(8000L);

        // 事件红线：事务内应用事件（AFTER_COMMIT 由 BillingEventPublisher 转 MQ），eventType+载荷四字段
        ArgumentCaptor<BillingDomainEvent> eventCaptor = ArgumentCaptor.forClass(BillingDomainEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        BillingDomainEvent event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(BillingMessagingConstants.EVENT_ARREARS_APPROVED);
        ArrearsApprovedPayload payload = (ArrearsApprovedPayload) event.payload();
        assertThat(payload.visitId()).isEqualTo(VISIT);
        assertThat(payload.approvalNo()).isEqualTo(NO);
        assertThat(payload.approvedAt()).isNotNull();
        assertThat(payload.approvedBalance()).isEqualTo(8000L);
    }

    @Test
    @DisplayName("⑧ 补面：无押金账户（或余额空）快照 0 分——预审端口同口径")
    void approveWithoutDepositAccountSnapshotsZero() {
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(pendingApproval());
        when(depositAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(arrearsApprovalMapper.casDecide(eq(NO), eq("APPROVED"), eq("EMP-001"), eq(0L)))
                .thenReturn(1);

        service.approve(NO);

        verify(arrearsApprovalMapper).casDecide(NO, "APPROVED", "EMP-001", 0L);
    }

    @Test
    @DisplayName("守卫：审批单不存在 BILL-1032（404），零决出零事件")
    void approveMissingApprovalThrows1032() {
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.approve(NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.ARREARS_APPROVAL_NOT_FOUND));
        verify(arrearsApprovalMapper, never()).casDecide(any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("守卫：非待审批态（已决出/草稿）BILL-1033（409），零决出零事件")
    void approveDecidedApprovalThrows1033() {
        ArrearsApproval decided = pendingApproval();
        decided.setStatus(ArrearsApprovalStatus.APPROVED);
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(decided);

        assertThatThrownBy(() -> service.approve(NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.ARREARS_APPROVAL_STATE_NOT_ALLOWED));
        verify(arrearsApprovalMapper, never()).casDecide(any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("守卫：CAS 0 行（双审批人并发被抢）BILL-1033，不发事件（回滚不放行）")
    void approveConcurrentLostThrows1033() {
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(pendingApproval());
        when(depositAccountMapper.selectOne(any(Wrapper.class))).thenReturn(account(8000L));
        when(arrearsApprovalMapper.casDecide(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.approve(NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.ARREARS_APPROVAL_STATE_NOT_ALLOWED));
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("守卫：缺登录审批人上下文 BILL-1012（400）——资金审批缺审计要素不落不可追放行")
    void approveWithoutOperatorContextThrows1012() {
        OperatorContextHolder.clear();
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(pendingApproval());

        assertThatThrownBy(() -> service.approve(NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING));
        verify(arrearsApprovalMapper, never()).casDecide(any(), any(), any(), any());
    }

    @Test
    @DisplayName("⑨ 驳回保持：PENDING_APPROVAL→REJECTED 终态留痕（approved_balance 不置值），不发事件")
    void rejectKeepsRejectedWithoutEvent() {
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(pendingApproval());
        when(arrearsApprovalMapper.casDecide(NO, "REJECTED", "EMP-001", null)).thenReturn(1);

        ArrearsApprovalVO vo = service.reject(NO);

        assertThat(vo.status()).isEqualTo(ArrearsApprovalStatus.REJECTED.getCode());
        assertThat(vo.approvedBalance()).isNull();
        verify(arrearsApprovalMapper).casDecide(eq(NO), eq("REJECTED"), eq("EMP-001"), isNull());
        verify(events, never()).publishEvent(any(BillingDomainEvent.class));
    }

    @Test
    @DisplayName("⑨ 补面：驳回 CAS 0 行并发被抢 BILL-1033")
    void rejectConcurrentLostThrows1033() {
        when(arrearsApprovalMapper.selectOne(any(Wrapper.class))).thenReturn(pendingApproval());
        when(arrearsApprovalMapper.casDecide(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reject(NO))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.ARREARS_APPROVAL_STATE_NOT_ALLOWED));
    }

    /** 待审批态审批单夹具 */
    private ArrearsApproval pendingApproval() {
        ArrearsApproval approval = new ArrearsApproval();
        approval.setId(9001L);
        approval.setApprovalNo(NO);
        approval.setVisitId(VISIT);
        approval.setApplyReason("押金不足暂欠");
        approval.setStatus(ArrearsApprovalStatus.PENDING_APPROVAL);
        return approval;
    }

    /** 押金账户夹具（余额=入参分值） */
    private DepositAccount account(long balance) {
        DepositAccount account = new DepositAccount();
        account.setVisitId(VISIT);
        account.setBalance(balance);
        return account;
    }
}
