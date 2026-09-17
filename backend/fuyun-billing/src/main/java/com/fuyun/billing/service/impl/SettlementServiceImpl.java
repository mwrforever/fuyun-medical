package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.PaymentLine;
import com.fuyun.billing.dto.SettleRequest;
import com.fuyun.billing.dto.SettlementPreviewRequest;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.entity.Settlement;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.PayerType;
import com.fuyun.billing.enums.PaymentMethod;
import com.fuyun.billing.enums.SettlementStatus;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.mapper.SettlementMapper;
import com.fuyun.billing.service.ISettlementService;
import com.fuyun.billing.vo.SettlementPreviewVO;
import com.fuyun.billing.vo.SettlementVO;
import com.fuyun.common.exception.BizException;
import com.fuyun.patient.api.CardAccountLedger;
import com.fuyun.patient.api.CardTxnRecord;
import com.fuyun.patient.api.CardTxnType;
import com.fuyun.patient.api.VisitIdValidator;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算服务（billing.settlement，FU-M13-03 收款侧；Spec §5 结算状态机 + §3.2 分级同步）。
 *
 * <p>红线：金额全部服务端计算与勾稽（明细合计=结算总额、支付明细合计=总额，两层，Spec §9）；
 * 医保拆分按预结算回执落库、本地不自行计算基金拆分；已结算只读（红线 2）。
 * 就诊卡余额收付经 M02 {@link CardAccountLedger}（D-13 收口后首次真实调用，同事务原子记账不超扣）。
 * 事件 billing.settlement.completed 在结算落库事务提交后发（AFTER_COMMIT，M03 据此放行发药）。
 */
@Slf4j
public class SettlementServiceImpl extends ServiceImpl<SettlementMapper, Settlement> implements ISettlementService {

    private final FeeRecordMapper feeRecordMapper;

    private final CardAccountLedger cardAccountLedger;

    private final ApplicationEventPublisher events;

    /** 全参构造器（InsuranceGateway/IInsuranceCallLogService 两成员随 Task 15 医保分支回填增设）。 */
    public SettlementServiceImpl(
            FeeRecordMapper feeRecordMapper, CardAccountLedger cardAccountLedger, ApplicationEventPublisher events) {
        this.feeRecordMapper = feeRecordMapper;
        this.cardAccountLedger = cardAccountLedger;
        this.events = events;
    }

    /**
     * 预结算（划价收款依据）。
     *
     * @param req 预结算请求（patientId/visitId/payerType），组件冻结见接口块
     * @return 结算单草稿（自费 DRAFT / 医保 PRESETTLED 锁价）
     * @throws BizException BILL-1008（409 无待结算费用）/ BILL-1006（409 医保 payer 下存在未贯标费用行）/
     *                      BILL-1016（勾稽不平）/ BILL-1024（医保通道业务失败）
     */
    @Override
    @Transactional
    public SettlementPreviewVO preview(SettlementPreviewRequest req) {
        // 费用期起止=本次纳入结算费用行的计费时刻极值（门诊当日场景收敛为同一时刻）
        List<FeeRecord> fees = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .eq(FeeRecord::getVisitId, req.visitId())
                .eq(FeeRecord::getStatus, FeeStatus.PENDING)
                .orderByAsc(FeeRecord::getId));
        if (fees.isEmpty()) {
            throw new BizException(BillingErrorCode.PRICING_UNAVAILABLE, HttpStatus.CONFLICT, "无待结算费用");
        }
        long total = fees.stream().mapToLong(FeeRecord::getAmount).sum();
        Settlement st = new Settlement();
        st.setSettleNo("S" + System.nanoTime());
        st.setPatientId(req.patientId());
        st.setVisitId(req.visitId());
        st.setSettleType(req.visitId().startsWith(VisitIdValidator.TYPE_INPATIENT) ? VisitType.IN : VisitType.OUT);
        st.setPayerType(req.payerType());
        st.setTotalAmount(total);
        st.setFeeStart(fees.stream()
                .map(FeeRecord::getChargedAt)
                .min(Comparator.naturalOrder())
                .orElseThrow());
        st.setFeeEnd(fees.stream()
                .map(FeeRecord::getChargedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow());
        if (req.payerType() == PayerType.SELF_PAY) {
            // 自费：本地聚合，全自费，无医保拆分
            st.setSelfExpenseAmount(total);
            st.setStatus(SettlementStatus.DRAFT);
        } else {
            // 医保分支（贯标硬校验 BILL-1006 → InsuranceGateway 预结算取回基金拆分并锁价 → 五拆分
            //   勾稽 BILL-1016 → insurance_call_log 2102 业务级留痕 → PRESETTLED）随 Task 15 网关
            //   契约与模拟适应器交付整体回填（brief Step 3b：执行顺序统一 Task 15 先落网关接口骨架）；
            //   回填前医保 payer 显式拒（502 通道不可用口径），禁产出缺基金拆分的半截结算单
            // TODO(Task 15): 医保分支回填——InsuranceGateway.preSettle + 五拆分勾稽 + callLogService.record(2102 留痕)
            log.warn(
                    "医保通道未接入，预结算显式拒：visit={}，payer={}",
                    req.visitId(),
                    req.payerType().getCode());
            throw new BizException(
                    BillingErrorCode.INSURANCE_CALL_FAILED,
                    HttpStatus.BAD_GATEWAY,
                    "医保通道未接入，暂不支持医保支付类型预结算：" + req.payerType().getCode());
        }
        save(st);
        log.info(
                "预结算草稿落库：settleNo={}，total={}分，visit={}，payer={}",
                st.getSettleNo(),
                st.getTotalAmount(),
                st.getVisitId(),
                st.getPayerType().getCode());
        return SettlementPreviewVO.from(st);
    }

    /**
     * 正式结算（幂等以 settleNo 终态为流水号锚点：重放同单直返不重复扣费——医保通道的
     * 请求级流水号语义由 Task 15 insurance_call_log 承载，自费通道不再冗余建键）。
     *
     * @param req 结算请求（settleNo 非空；payments 支付明细行非空——CARD_BALANCE 行 channelRef=卡
     *            账户 id 字符串，组件冻结见接口块）
     * @return 结算出参
     * @throws BizException BILL-1014（404 缺单）/ BILL-1015（409 状态不允许）/ BILL-1016（409 两层
     *                      勾稽任一不平）/ BILL-1012（400 CARD_BALANCE 行缺/非法卡账户引用或多卡混付）；
     *                      PAT-1013/1014/1016（就诊卡记账失败经调用方事务回滚上抛）
     */
    @Override
    @Transactional
    public SettlementVO settle(SettleRequest req) {
        // 请求对象解构为局部名（A.7-1 入参对象化；后续编排逻辑与收敛前语义逐字一致）
        String settleNo = req.settleNo();
        List<PaymentLine> payments = req.payments();
        Settlement st = lambdaQuery().eq(Settlement::getSettleNo, settleNo).one();
        if (st == null) {
            throw new BizException(BillingErrorCode.SETTLEMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "结算单不存在");
        }
        // 幂等：已 SETTLED 直返（结算交易以流水号管理，重复请求不重扣——Spec 调研依据 7）
        if (st.getStatus() == SettlementStatus.SETTLED) {
            log.info("结算幂等命中：settleNo={}，已结算直返", settleNo);
            return SettlementVO.from(st);
        }
        if (st.getStatus() != SettlementStatus.DRAFT && st.getStatus() != SettlementStatus.PRESETTLED) {
            throw new BizException(BillingErrorCode.SETTLEMENT_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "结算单状态不允许结算");
        }
        // 两层勾稽第二层：Σpayments.amount == 结算总额（Spec §9 支付层——先勾稽后动卡，不平不扣）
        long paySum = 0L;
        long cardPayFen = 0L;
        String cardAccountIdRef = null;
        for (PaymentLine p : payments) {
            paySum += p.amount();
            if (p.method() == PaymentMethod.CARD_BALANCE) {
                // 扣卡额=CARD_BALANCE 行 amount 求和（2026-09-17 裁决①；acct_pay_amount 系医保个账
                //   拆分列，语义不同，不再误作扣卡额）；同卡账户引用一致方可合并一笔出账
                cardPayFen += p.amount();
                if (cardAccountIdRef == null) {
                    cardAccountIdRef = p.channelRef();
                } else if (!cardAccountIdRef.equals(p.channelRef())) {
                    throw new BizException(
                            BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING,
                            HttpStatus.BAD_REQUEST,
                            "同一结算仅允许单张就诊卡支付（CARD_BALANCE 行 channelRef 必须一致）");
                }
            }
        }
        if (paySum != st.getTotalAmount()) {
            log.warn("支付勾稽不平：settleNo={}，支付合计={}，结算总额={}", settleNo, paySum, st.getTotalAmount());
            throw new BizException(BillingErrorCode.AMOUNT_MISMATCH, HttpStatus.CONFLICT, "支付明细合计与结算总额不符，禁止结算");
        }
        // 就诊卡余额支付：调 M02 台账原子记账（PAY 出账，余额不足 PAT-1016 由调用方事务回滚）；无卡行不调
        if (cardPayFen > 0L) {
            cardAccountLedger.record(
                    new CardTxnRecord(parseCardAccountId(cardAccountIdRef), CardTxnType.PAY, cardPayFen, settleNo));
        }
        List<FeeRecord> fees = feeRecordMapper.selectList(Wrappers.<FeeRecord>lambdaQuery()
                .eq(FeeRecord::getVisitId, st.getVisitId())
                .eq(FeeRecord::getStatus, FeeStatus.PENDING)
                .orderByAsc(FeeRecord::getId));
        long feeSum = fees.stream().mapToLong(FeeRecord::getAmount).sum();
        // 三层勾稽第一层：明细合计=结算总额
        if (feeSum != st.getTotalAmount()) {
            log.warn("金额勾稽不平：settleNo={}，明细合计={}，结算总额={}", settleNo, feeSum, st.getTotalAmount());
            throw new BizException(BillingErrorCode.AMOUNT_MISMATCH, HttpStatus.CONFLICT, "费用明细合计与结算总额不符，禁止结算");
        }
        // 数据库写操作：费用批量置 SETTLED 并回填结算引用（同事务）
        for (FeeRecord fee : fees) {
            fee.setStatus(FeeStatus.SETTLED);
            fee.setSettlementId(st.getId());
            feeRecordMapper.updateById(fee);
        }
        // 支付明细落库（V603 payment_details 列形态 [{method,amount,channelRef}]；退费 execute
        //   原路退回自此读回卡账户引用与各行金额）
        st.setPaymentDetails(toPaymentDetailsJson(payments));
        st.setStatus(SettlementStatus.SETTLED);
        st.setSettledAt(OffsetDateTime.now());
        updateById(st);
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：AFTER_COMMIT 经 BillingEventPublisher 出 fy.topic，
        //   结算回滚则广播不出（M03 据 settlement.completed 放行发药，禁误放）
        events.publishEvent(new BillingDomainEvent(
                BillingMessagingConstants.EVENT_SETTLEMENT_COMPLETED,
                new SettlementCompletedPayload(
                        st.getId(),
                        st.getSettleNo(),
                        st.getPatientId(),
                        st.getVisitId(),
                        st.getSettleType().getCode(),
                        st.getPayerType().getCode(),
                        st.getTotalAmount(),
                        st.getPooledAmount(),
                        st.getAcctPayAmount(),
                        st.getSelfPayAmount())));
        log.info("结算完成：settleNo={}，total={}分，visit={}", settleNo, st.getTotalAmount(), st.getVisitId());
        return SettlementVO.from(st);
    }

    /** 按结算编号查结算单（GET /settlements/{no}；纯读出口，已结算只读红线 2 下无状态迁移副作用）。 */
    @Override
    @Transactional(readOnly = true)
    public SettlementVO getByNo(String settleNo) {
        Settlement st = lambdaQuery().eq(Settlement::getSettleNo, settleNo).one();
        if (st == null) {
            throw new BizException(BillingErrorCode.SETTLEMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "结算单不存在");
        }
        return SettlementVO.from(st);
    }

    /**
     * CARD_BALANCE 行 channelRef → 卡账户 id（前端 string 承载 id，web A.3-6）：空/非数字均为
     * 资金动作定位要素缺失，BILL-1012 显式拒（禁静默错扣，禁裸 parseLong 抛 500 出契约外形态）。
     *
     * @param channelRef 卡账户 id 字符串，可空（空即拒）
     * @return 卡账户 id
     * @throws BizException BILL-1012（400 缺失或非法卡账户引用）
     */
    private static long parseCardAccountId(String channelRef) {
        if (channelRef == null || channelRef.isBlank()) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING, HttpStatus.BAD_REQUEST, "就诊卡支付行缺少卡账户引用 channelRef");
        }
        try {
            return Long.parseLong(channelRef);
        } catch (NumberFormatException e) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING,
                    HttpStatus.BAD_REQUEST,
                    "就诊卡支付行 channelRef 必须为卡账户 id：" + channelRef);
        }
    }

    /**
     * 支付明细行 → payment_details JSON 文本（V603 列注释 [{method,amount,channelRef}] 同源）：
     * Jackson ObjectNode 组装（第 2 轮审查 P2-3——手工拼引号在 channelRef 含引号/反斜杠时可产出
     * 破损甚至结构逃逸 JSON，杜绝注入面）；channelRef null 用 putNull 保三键形态稳定；
     * JsonNodeFactory 直组装不经 Spring 全局 ObjectMapper，免 Long→String 序列化模块改写列形态
     * （amount 恒数值，Task 13 execute 读回解析同源）；零新依赖，形态断言见 Step 1 paymentDetails 用例。
     *
     * @param payments 支付明细行，非空（元素非空，@Valid 边界已保）
     * @return JSON 数组文本，非空
     */
    private static String toPaymentDetailsJson(List<PaymentLine> payments) {
        ArrayNode details = JsonNodeFactory.instance.arrayNode();
        for (PaymentLine p : payments) {
            ObjectNode line = details.addObject();
            line.put("method", p.method().getCode());
            line.put("amount", p.amount());
            if (p.channelRef() == null) {
                line.putNull("channelRef");
            } else {
                line.put("channelRef", p.channelRef());
            }
        }
        return details.toString();
    }
}
