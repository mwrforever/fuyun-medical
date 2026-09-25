package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.fuyun.billing.service.IArrearsApprovalService;
import com.fuyun.billing.vo.ArrearsApprovalVO;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 挂账审批服务实现（IArrearsApprovalService 唯一实现，装配归 BillingWebConfig @Import）：
 * 创建（落库即待审批）与决出（approve/reject 共用 CAS 唯一可决出边）。
 *
 * <p>事件红线（GC8/A.4.2-7）：approve 在事务内经 ApplicationEventPublisher 发布应用事件，
 * AFTER_COMMIT 由 BillingEventPublisher 出 fy.topic——回滚不放行（M04 侧 READY 转移以
 * MQ 回执为唯一驱动，事务内外两层一致）。押金余额快照为决出时点读数（id 73 approvedBalance
 * 同源承载），非欠费额权威（欠费权威在 BillingAccountQueryPort 聚合面）。
 *
 * <p>资金审批权限（W-37）注记：本 PR 端点在位，权限全量收敛归 PR-4（计划 GC13）。
 * 线程安全：无状态单例，事务边界逐方法 @Transactional。
 */
@Slf4j
public class ArrearsApprovalServiceImpl implements IArrearsApprovalService {

    /** 审批单号前缀（业务号可读锚点；序列号沿用 feeNo 纳秒后缀演示形态，非资金键） */
    private static final String APPROVAL_NO_PREFIX = "AR";

    private final ArrearsApprovalMapper arrearsApprovalMapper;

    private final DepositAccountMapper depositAccountMapper;

    private final ApplicationEventPublisher events;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param arrearsApprovalMapper 挂账审批 mapper，非空；单号定位与 CAS 决出
     * @param depositAccountMapper  押金账户 mapper，非空；决出时点余额快照
     * @param events                应用事件发布器，非空；事务内发布（AFTER_COMMIT 转 MQ）
     */
    public ArrearsApprovalServiceImpl(
            ArrearsApprovalMapper arrearsApprovalMapper,
            DepositAccountMapper depositAccountMapper,
            ApplicationEventPublisher events) {
        this.arrearsApprovalMapper = arrearsApprovalMapper;
        this.depositAccountMapper = depositAccountMapper;
        this.events = events;
    }

    /** {@inheritDoc}：DRAFT→PENDING_APPROVAL 内联瞬时（落库即待审批态），单号服务端生成。 */
    @Override
    @Transactional
    public ArrearsApprovalVO create(ArrearsApprovalCreateRequest req) {
        ArrearsApproval approval = new ArrearsApproval();
        approval.setApprovalNo(APPROVAL_NO_PREFIX + System.nanoTime());
        approval.setVisitId(req.visitId());
        approval.setApplyReason(req.applyReason());
        approval.setStatus(ArrearsApprovalStatus.PENDING_APPROVAL);
        // 数据库写操作：挂账审批单落行（uk_arrears_approval_no 单号唯一兜底）
        arrearsApprovalMapper.insert(approval);
        log.info("挂账审批单创建：approvalNo={}，visitId={}", approval.getApprovalNo(), req.visitId());
        return ArrearsApprovalVO.from(approval);
    }

    /** {@inheritDoc}：CAS 决出 + 押金余额快照 + 事务内发布 arrears.approved（id 73）。 */
    @Override
    @Transactional
    public ArrearsApprovalVO approve(String approvalNo) {
        ArrearsApproval approval = requireApproval(approvalNo);
        // 状态前置守卫（读-写窗口内并发由 CAS 兜底 0 行同码拒）：非待审批态零决出零事件
        requireDecidable(approval);
        String approver = requireApprover();
        // 押金余额快照（决出时点读数，分）：无账户为 0——id 73 载荷 approvedBalance 同源承载
        Long depositBalance = depositBalanceOf(approval.getVisitId());
        // 数据库写操作：审批决出 CAS（PENDING_APPROVAL 唯一可决出边，并发被抢 0 行拒）
        int rows = arrearsApprovalMapper.casDecide(
                approvalNo, ArrearsApprovalStatus.APPROVED.getCode(), approver, depositBalance);
        if (rows == 0) {
            throw new BizException(
                    BillingErrorCode.ARREARS_APPROVAL_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "挂账审批状态竞态，决出失败：" + approvalNo);
        }
        // 事务内发应用事件（GC8 禁事务内直发 MQ）：AFTER_COMMIT 经 BillingEventPublisher 出 fy.topic
        events.publishEvent(new BillingDomainEvent(
                BillingMessagingConstants.EVENT_ARREARS_APPROVED,
                new ArrearsApprovedPayload(approval.getVisitId(), approvalNo, Instant.now(), depositBalance)));
        log.info(
                "挂账审批通过：approvalNo={}，visitId={}，审批人={}，押金余额快照={}分，arrears.approved 发布待提交",
                approvalNo,
                approval.getVisitId(),
                approver,
                depositBalance);
        return ArrearsApprovalVO.decided(approval, approver, ArrearsApprovalStatus.APPROVED, depositBalance);
    }

    /** {@inheritDoc}：CAS 决出终态留痕，不发事件（登记面无驳回契约）。 */
    @Override
    @Transactional
    public ArrearsApprovalVO reject(String approvalNo) {
        ArrearsApproval approval = requireApproval(approvalNo);
        requireDecidable(approval);
        String approver = requireApprover();
        // 数据库写操作：审批驳回 CAS（approved_balance 不置值——REJECTED 无放行语义）
        int rows =
                arrearsApprovalMapper.casDecide(approvalNo, ArrearsApprovalStatus.REJECTED.getCode(), approver, null);
        if (rows == 0) {
            throw new BizException(
                    BillingErrorCode.ARREARS_APPROVAL_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "挂账审批状态竞态，决出失败：" + approvalNo);
        }
        log.info("挂账审批驳回：approvalNo={}，visitId={}，审批人={}", approvalNo, approval.getVisitId(), approver);
        return ArrearsApprovalVO.decided(approval, approver, ArrearsApprovalStatus.REJECTED, null);
    }

    /**
     * 决出前置状态守卫（PENDING_APPROVAL 唯一可决出边——已决出/草稿态 fail-fast 拒，禁触库写入面）。
     *
     * @param approval 审批单实体，非空
     * @throws BizException BILL-1033（409 非 PENDING_APPROVAL 态）
     */
    private static void requireDecidable(ArrearsApproval approval) {
        if (approval.getStatus() != ArrearsApprovalStatus.PENDING_APPROVAL) {
            throw new BizException(
                    BillingErrorCode.ARREARS_APPROVAL_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅待审批挂账单可决出，当前状态：" + approval.getStatus().getCode());
        }
    }

    /**
     * 审批单定位守卫（单号唯一索引承载）。
     *
     * @param approvalNo 审批单号，非空
     * @return 审批单实体，非空
     * @throws BizException BILL-1032（404 单号不存在）
     */
    private ArrearsApproval requireApproval(String approvalNo) {
        // 数据库读操作：按审批单号定位（uk_arrears_approval_no 唯一；缺失即凭证无效）
        ArrearsApproval approval = arrearsApprovalMapper.selectOne(
                Wrappers.<ArrearsApproval>lambdaQuery().eq(ArrearsApproval::getApprovalNo, approvalNo));
        if (approval == null) {
            throw new BizException(
                    BillingErrorCode.ARREARS_APPROVAL_NOT_FOUND, HttpStatus.NOT_FOUND, "挂账审批单不存在：" + approvalNo);
        }
        return approval;
    }

    /**
     * 审批操作者守卫（GC22：操作者恒取登录上下文，禁前端传人）——资金审批缺审计要素不落
     * 不可追放行（BILL-1012 语义扩展先例同 BILL-1023，不新增码位）。
     *
     * @return 审批人工号，非空
     * @throws BizException BILL-1012（400 无登录操作者上下文）
     */
    private static String requireApprover() {
        String approver = OperatorContextHolder.get();
        if (approver == null || approver.isBlank()) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING, HttpStatus.BAD_REQUEST, "缺少登录审批人上下文，禁止挂账审批");
        }
        return approver;
    }

    /**
     * 决出时点押金余额快照（一就诊一账户 uk_deposit_visit；无账户或余额空为 0——预审端口同口径）。
     *
     * @param visitId CF-3 住院就诊号，非空
     * @return 押金余额（分），非空
     */
    private Long depositBalanceOf(String visitId) {
        // 数据库读操作：押金账户余额读取（快照语义，不参与后续资金动作）
        DepositAccount account = depositAccountMapper.selectOne(
                Wrappers.<DepositAccount>lambdaQuery().eq(DepositAccount::getVisitId, visitId));
        return account == null || account.getBalance() == null ? 0L : account.getBalance();
    }
}
