package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.DepositChangedPayload;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.DepositRequest;
import com.fuyun.billing.entity.DepositAccount;
import com.fuyun.billing.entity.DepositTxn;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.DepositStatus;
import com.fuyun.billing.enums.DepositTxnStatus;
import com.fuyun.billing.enums.DepositTxnType;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.PaymentMethod;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.DepositAccountMapper;
import com.fuyun.billing.mapper.DepositTxnMapper;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.properties.BillingProperties;
import com.fuyun.billing.service.IDepositService;
import com.fuyun.billing.vo.DepositAccountVO;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.OffsetDateTime;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 押金服务（billing.deposit_account/deposit_txn，FU-M13-04 住院预交金）。
 *
 * <p>红线：余额唯一写点为 {@link DepositAccountMapper#mutateBalance} 单语句原子 UPDATE RETURNING
 * 回读（并发缴存/抵扣串行化，欠费判定以回读值为准，应用层禁散改 balance）；欠费阈值判定
 * NORMAL⇄ARREARS（有效余额=回读余额−已确认未结算费用）；billing.deposit.changed 在事务提交后发
 * （AFTER_COMMIT，M01 通知/M04 欠费提醒与出院放行校验消费）；门诊预交金按 2025-03 国家政策取消
 * 不设账户（Spec §12 澄清①），就诊号守卫仅受理住院 I 前缀。
 */
@Slf4j
public class DepositServiceImpl extends ServiceImpl<DepositAccountMapper, DepositAccount> implements IDepositService {

    /** 缴存受理支付方式（V604 deposit_txn.payment_method 列口径四值；CARD_BALANCE/CHARGE_ON_CREDIT 仅结算支付明细域） */
    private static final Set<PaymentMethod> DEPOSIT_CHANNELS =
            Set.of(PaymentMethod.CASH, PaymentMethod.BANK, PaymentMethod.SCAN, PaymentMethod.ONLINE);

    private final DepositTxnMapper depositTxnMapper;

    private final FeeRecordMapper feeRecordMapper;

    private final ApplicationEventPublisher events;

    private final BillingProperties properties;

    /**
     * 全参构造器（装配归 fuyun-app 侧 config @Import——Task 16 交付）。
     *
     * @param depositTxnMapper 押金流水 mapper（缴存流水落库），非空
     * @param feeRecordMapper  费用行 mapper（已确认未结算费用聚合，欠费判定数据源），非空
     * @param events           应用事件发布器（deposit.changed 事务提交后出 MQ），非空
     * @param properties       收费域参数（欠费预警默认阈值），非空
     */
    public DepositServiceImpl(
            DepositTxnMapper depositTxnMapper,
            FeeRecordMapper feeRecordMapper,
            ApplicationEventPublisher events,
            BillingProperties properties) {
        this.depositTxnMapper = depositTxnMapper;
        this.feeRecordMapper = feeRecordMapper;
        this.events = events;
        this.properties = properties;
    }

    /**
     * 住院预交金缴存（多渠道：窗口/自助机/扫码/线上）。
     *
     * <p>执行流程：三守卫（就诊号/操作者/支付方式）→ 一就诊一账户定位（缺户即零余额开户，
     * SETTLED/CLOSED 终态拒缴）→ mutateBalance 原子记账回读（回读落空=并发终态竞争，显式拒）→
     * 欠费阈值判定并落状态迁移 → 缴存流水 ACTIVE 落库 → 事务内发 deposit.changed（AFTER_COMMIT 出 MQ）。
     *
     * @param req 缴存请求，非空；金额>0（@Positive 边界已保）
     * @return 新缴存流水 id
     * @throws BizException BILL-1013（400 就诊号非住院 I 前缀 14 位定长）/
     *                      BILL-1012（400 缺登录操作者上下文；400 支付方式混入 CARD_BALANCE/CHARGE_ON_CREDIT）/
     *                      BILL-1023（409 账户 SETTLED/CLOSED 终态拒缴存；409 并发窗口回读落空）
     */
    @Override
    @Transactional
    public long deposit(DepositRequest req) {
        // 就诊号守卫：押金业务唯一受理住院 I 前缀（门诊预交金按国家政策取消，Spec §12 澄清①）
        requireInpatientVisitId(req.visitId());
        // 操作者守卫：缴存是资金动作，无登录上下文不可追溯即显式拒（退费申请同款红线 3）
        String operator = OperatorContextHolder.get();
        if (operator == null || operator.isBlank()) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING, HttpStatus.BAD_REQUEST, "缺少登录操作者上下文，禁止押金缴存");
        }
        // 支付方式守卫：deposit_txn.payment_method 列仅 CASH/BANK/SCAN/ONLINE 四值（V604 列口径），
        //   CARD_BALANCE/CHARGE_ON_CREDIT 属结算支付明细域，混入缴存通道显式拒（A.3-4 码义扩展，不新增码位）
        if (!DEPOSIT_CHANNELS.contains(req.paymentMethod())) {
            log.warn(
                    "押金缴存支付方式不受理：visit={}，method={}",
                    req.visitId(),
                    req.paymentMethod().getCode());
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING,
                    HttpStatus.BAD_REQUEST,
                    "押金缴存仅支持 CASH/BANK/SCAN/ONLINE：" + req.paymentMethod().getCode());
        }
        // 数据库读操作：一就诊一账户定位（uk_deposit_visit）
        DepositAccount account =
                lambdaQuery().eq(DepositAccount::getVisitId, req.visitId()).one();
        if (account == null) {
            // 开户：余额唯一写点红线禁 insert 带额，先零余额建户再走原子记账；阈值仅开户时生效
            account = new DepositAccount();
            account.setPatientId(req.patientId());
            account.setVisitId(req.visitId());
            account.setBalance(0L);
            account.setWarningThreshold(
                    req.warningThresholdFen() != null
                            ? req.warningThresholdFen()
                            : properties.depositWarningThresholdFen());
            account.setStatus(DepositStatus.NORMAL);
            save(account);
            log.info(
                    "押金账户开户：visit={}，account={}，warningThreshold={}分",
                    req.visitId(),
                    account.getId(),
                    account.getWarningThreshold());
        } else if (account.getStatus() != DepositStatus.NORMAL && account.getStatus() != DepositStatus.ARREARS) {
            // 终态拒缴存：SETTLED 出院结算抵扣完成/CLOSED 销户均不可再缴（原路语义，BILL-1023 前置校验）
            log.warn(
                    "押金账户终态拒缴存：visit={}，account={}，status={}",
                    req.visitId(),
                    account.getId(),
                    account.getStatus().getCode());
            throw new BizException(
                    BillingErrorCode.DEPOSIT_TXN_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "押金账户已终态，拒绝缴存：" + account.getStatus().getCode());
        }
        // 原子记账：单语句 UPDATE RETURNING 回读（并发缴存/抵扣/结算抵扣串行化，欠费判定以回读值为准）；
        //   回读 null=并发窗口内账户被终态化（出院结算抢先），按 BILL-1023 显式拒且不落孤儿流水
        Long balance = baseMapper.mutateBalance(account.getId(), req.amountFen());
        if (balance == null) {
            log.warn("押金原子记账回读落空（并发终态竞争）：visit={}，account={}", req.visitId(), account.getId());
            throw new BizException(
                    BillingErrorCode.DEPOSIT_TXN_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "押金账户状态已变更，缴存未入账，请重试");
        }
        // 数据库读操作：已确认未结算费用聚合（欠费预警人群，Spec §6——余额与已确认费用结合计算）
        long confirmedSum = feeRecordMapper
                .selectList(Wrappers.<FeeRecord>lambdaQuery()
                        .eq(FeeRecord::getVisitId, req.visitId())
                        .eq(FeeRecord::getStatus, FeeStatus.CONFIRMED))
                .stream()
                .mapToLong(FeeRecord::getAmount)
                .sum();
        // 欠费阈值判定：有效余额=回读余额−已确认费用，低于阈值 ARREARS、回升 NORMAL（恰等于阈值不算欠费）
        DepositStatus evaluated =
                balance - confirmedSum < account.getWarningThreshold() ? DepositStatus.ARREARS : DepositStatus.NORMAL;
        if (account.getStatus() != evaluated) {
            // 数据库写操作：状态迁移落库（首次建户默认 NORMAL，此处覆盖开户即欠费与回升两类迁移）
            account.setStatus(evaluated);
            updateById(account);
            log.warn("押金账户状态切换：visit={}，account={}，status={}", req.visitId(), account.getId(), evaluated.getCode());
        }
        // 数据库写操作：缴存流水落库——金额恒正、方向由 txn_type 表达（DEPOSIT 缴入）
        DepositTxn txn = new DepositTxn();
        txn.setAccountId(account.getId());
        txn.setTxnType(DepositTxnType.DEPOSIT);
        txn.setAmount(req.amountFen());
        txn.setPaymentMethod(req.paymentMethod());
        txn.setChannelRef(req.channelRef());
        txn.setOperator(operator);
        txn.setStatus(DepositTxnStatus.ACTIVE);
        txn.setOccurredAt(OffsetDateTime.now());
        depositTxnMapper.insert(txn);
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：AFTER_COMMIT 经 BillingEventPublisher 出 fy.topic，
        //   M01 欠费通知/M04 欠费提醒与出院放行校验消费；事务回滚则广播不出
        events.publishEvent(new BillingDomainEvent(
                BillingMessagingConstants.EVENT_DEPOSIT_CHANGED,
                new DepositChangedPayload(
                        account.getId(), account.getPatientId(), req.visitId(), balance, evaluated.getCode())));
        log.info(
                "押金缴存完成：visit={}，account={}，txn={}，amount={}分，balance={}分，status={}",
                req.visitId(),
                account.getId(),
                txn.getId(),
                req.amountFen(),
                balance,
                evaluated.getCode());
        return txn.getId();
    }

    /**
     * 按就诊号查押金账户（纯读出口，无状态迁移副作用）。
     *
     * @param visitId CF-3 住院就诊号，非空
     * @return 账户出参，非空
     * @throws BizException BILL-1013（400 就诊号非住院形态）/ BILL-1022（404 无账户）
     */
    @Override
    @Transactional(readOnly = true)
    public DepositAccountVO getByVisit(String visitId) {
        requireInpatientVisitId(visitId);
        DepositAccount account =
                lambdaQuery().eq(DepositAccount::getVisitId, visitId).one();
        if (account == null) {
            throw new BizException(
                    BillingErrorCode.DEPOSIT_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "押金账户不存在：" + visitId);
        }
        return DepositAccountVO.from(account);
    }

    /**
     * 住院就诊号守卫：CF-3 结构合法且 I 前缀方可参与押金业务（账户/清单均住院域）。
     *
     * @param visitId 就诊号原文，允许为空（空即拒）
     * @throws BizException BILL-1013（400 结构非法或非住院前缀）
     */
    private static void requireInpatientVisitId(String visitId) {
        if (!VisitIdValidator.isValid(visitId) || !visitId.startsWith(VisitIdValidator.TYPE_INPATIENT)) {
            throw new BizException(
                    BillingErrorCode.VISIT_ID_MALFORMED,
                    HttpStatus.BAD_REQUEST,
                    "押金业务仅受理住院就诊号（I 前缀 14 位定长）：" + visitId);
        }
    }
}
