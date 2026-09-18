package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.constants.BillingMessagingConstants;
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
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.mapper.RefundFeeLinkMapper;
import com.fuyun.billing.mapper.RefundRequestMapper;
import com.fuyun.billing.mapper.SettlementMapper;
import com.fuyun.billing.properties.BillingRefundProperties;
import com.fuyun.billing.service.IRefundService;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.CardAccountLedger;
import com.fuyun.patient.api.CardTxnRecord;
import com.fuyun.patient.api.CardTxnType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退费服务（billing.refund，FU-M13-03 退侧；Spec §5 退费状态机 + §6 分级免审）。
 *
 * <p>红线：金额全部服务端按明细算与勾稽（单价快照×退费数量，禁前端传额，红线 1 退侧同源）；
 * 执行占用硬前置（已发药先退药，BILL-1017，M06 执行状态查询 P3 接入前以 exec_occupy_status 列承载）；
 * 双人守卫=审批人≠申请人（BILL-1020，等保三级分权）；免审阈值经 {@link BillingRefundProperties}
 * 注入（M01 参数中心就绪前 env 兜底）。退费负向表达收敛为 refund_fee_link 负向台账、不生成
 * fee_record 负向行（裁决⑫，link 表即负向账）；原路退回·就诊卡侧经 M02 {@link CardAccountLedger}
 * 台账 REFUND 入账（同事务原子记账），CARD_BALANCE 行按 channelRef 聚合单次入账（与写入侧
 * 求和扣款口径对称，2026-09-18 用户裁决并发收口）。事件 billing.refund.approved（免审直退
 * 同事件承载）在事务提交后发（AFTER_COMMIT，A.4.2-7 禁事务内直发 MQ）。分级审批（Spec §6：
 * 免审/一级/二级）由 {@link ApprovalLevel} 单一判定承载：L2 单一级批后转待二级且不发事件、
 * 二级批终批才发事件；收费组长/财务/医保办角色硬校验随 PR-5 RBAC 接线。并发收口：apply 目标
 * 费用行集 SELECT FOR UPDATE 行锁（{@link FeeRecordMapper#lockByIds}）串行化并发申请，锁内
 * 重读聚合做超可退守卫，根除「双读 refundedFen 互不可见双双过守卫」的 TOCTOU。
 */
@Slf4j
public class RefundServiceImpl extends ServiceImpl<RefundRequestMapper, RefundRequest> implements IRefundService {

    private final SettlementMapper settlementMapper;

    private final FeeRecordMapper feeRecordMapper;

    private final RefundFeeLinkMapper refundFeeLinkMapper;

    private final CardAccountLedger cardAccountLedger;

    private final ApplicationEventPublisher events;

    private final ObjectMapper objectMapper;

    private final BillingRefundProperties properties;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import）。
     *
     * @param settlementMapper   结算单 mapper（原路退回读 payment_details），非空
     * @param feeRecordMapper    费用行 mapper（明细算额/判态迁移），非空
     * @param refundFeeLinkMapper 退费关联 mapper（负向台账落表与聚合），非空
     * @param cardAccountLedger  一卡通台账（CARD_BALANCE 支付项 REFUND 入账），非空
     * @param events             应用事件发布器（refund.approved 事务提交后出 MQ），非空
     * @param objectMapper       JSON 解析器（payment_details 读回），非空
     * @param properties         退费分级参数（免审阈值），非空
     */
    public RefundServiceImpl(
            SettlementMapper settlementMapper,
            FeeRecordMapper feeRecordMapper,
            RefundFeeLinkMapper refundFeeLinkMapper,
            CardAccountLedger cardAccountLedger,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper,
            BillingRefundProperties properties) {
        this.settlementMapper = settlementMapper;
        this.feeRecordMapper = feeRecordMapper;
        this.refundFeeLinkMapper = refundFeeLinkMapper;
        this.cardAccountLedger = cardAccountLedger;
        this.events = events;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 退费申请（免审阈值内当日未占用直退 APPROVED，其余按分级进 PENDING_APPROVAL 待一审）。
     *
     * <p>执行流程：登录上下文取申请人 → 目标费用行集 SELECT FOR UPDATE 行锁串行化并发申请 →
     * 锁内逐行守卫（缺行/执行占用/超可退）并服务端算额 → 分级判定（医保结算退费一律
     * SETTLED_REFUND；自费按计费日当日/跨日）→ 审批级别判定（{@link #resolveApprovalLevel}：
     * L0 免审直退落库即 APPROVED；L1/L2 均先落 PENDING_APPROVAL，L2 由 approve 一级批后升待二级）
     * → 申请单与 link 负向台账同事务落表 → 免审命中即发 refund.approved。
     *
     * <p>并发收口（2026-09-18 用户裁决）：行锁前置于守卫——并发双申请同费用行时后到者阻塞至
     * 先到者提交，锁内重读的费用行与 refundedFen 已退聚合（每条语句取新快照）均含先到者已落
     * 的 APPROVED 负向台账，超可退守卫即拒；免审阈值内「双双自动批准全自动重复退款」的
     * TOCTOU 窗口就此根除（uk_refund_fee 仅 (refund_id,fee_id) 无跨申请保护，靠本行锁补位）。
     *
     * @param req 退费申请请求（settlementId/lines/reason），非空；金额入参无字段（红线 1）
     * @return 新退费申请 id
     * @throws BizException BILL-1012（400 缺登录操作者上下文——不可追溯资金动作显式拒）/
     *                      BILL-1014（404 缺原结算单）/ BILL-1010（404 费用行缺行脏数据）/
     *                      BILL-1017（409 执行占用硬前置——已发药/已执行须先逆向业务）/
     *                      BILL-1021（409 超可退余额，含历史已退聚合与并发先到申请）
     */
    @Override
    @Transactional
    public long apply(RefundApplyRequest req) {
        // 申请人恒取登录上下文（红线 3 退侧同款：操作者不由前端传；无上下文不可追溯即拒）
        String applicant = OperatorContextHolder.get();
        if (applicant == null || applicant.isBlank()) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING, HttpStatus.BAD_REQUEST, "缺少登录操作者上下文，禁止退费申请");
        }
        // 数据库读操作：原结算单定位（patientId/visitId/payerType 分级判定的数据源）
        Settlement st = settlementMapper.selectById(req.settlementId());
        if (st == null) {
            throw new BizException(
                    BillingErrorCode.SETTLEMENT_NOT_FOUND, HttpStatus.NOT_FOUND, "结算单不存在：" + req.settlementId());
        }
        // 行锁抢占目标费用行集至事务提交（并发收口锚点）：后到并发申请在此阻塞，先到者提交后
        //   本事务读到的行与下方 refundedFen 聚合即最新口径——守卫读值不再是过期快照
        List<Long> feeIds =
                req.lines().stream().map(RefundLine::feeId).distinct().toList();
        Map<Long, FeeRecord> lockedFees =
                feeRecordMapper.lockByIds(feeIds).stream().collect(Collectors.toMap(FeeRecord::getId, f -> f));
        // 逐行守卫与服务端算额（锁内口径）：行金额=单价快照×退费数量 HALF_UP 取整到分（与计价同口径）
        long amount = 0L;
        boolean crossDay = false;
        List<Long> lineAmounts = new ArrayList<>(req.lines().size());
        for (RefundLine line : req.lines()) {
            // 锁内读回的行即最新已提交版本（缺行=费用不存在，原 BILL-1010 语义保留）
            FeeRecord fee = lockedFees.get(line.feeId());
            if (fee == null) {
                throw new BizException(BillingErrorCode.FEE_NOT_FOUND, HttpStatus.NOT_FOUND, "费用记录不存在：" + line.feeId());
            }
            // 执行占用硬前置（Spec §6「已发药先退药」硬绑定——杜绝只退钱不退业务）
            if (fee.getExecOccupyStatus() != ExecOccupyStatus.NONE) {
                log.warn(
                        "退费明细执行占用拦截：refundSettlement={}，feeId={}，occupy={}",
                        st.getSettleNo(),
                        fee.getId(),
                        fee.getExecOccupyStatus().getCode());
                throw new BizException(
                        BillingErrorCode.REFUND_BLOCKED_BY_EXEC_OCCUPY,
                        HttpStatus.CONFLICT,
                        "费用已发生执行占用（已发药/已执行），须先逆向业务再退费：" + fee.getId());
            }
            long lineAmount = BigDecimal.valueOf(fee.getUnitPriceSnapshot())
                    .multiply(line.refundQuantity())
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            // 超可退守卫：历史已退（APPROVED/EXECUTED 态 link 负向聚合）+ 本次 > 费用行金额 → 拒
            long alreadyRefunded = refundedFen(fee.getId());
            if (alreadyRefunded + lineAmount > fee.getAmount()) {
                log.warn(
                        "退费超可退拦截：refundSettlement={}，feeId={}，行金额={}分，已退={}分，原额={}分",
                        st.getSettleNo(),
                        fee.getId(),
                        lineAmount,
                        alreadyRefunded,
                        fee.getAmount());
                throw new BizException(
                        BillingErrorCode.REFUND_AMOUNT_EXCEEDED, HttpStatus.CONFLICT, "退费金额超可退余额：" + fee.getId());
            }
            lineAmounts.add(lineAmount);
            amount += lineAmount;
            // 跨日判定：任一费用行计费日早于今日 → 全单按跨日分级（保守须审批）
            if (!LocalDate.now().equals(fee.getBillingDate())) {
                crossDay = true;
            }
        }
        // 分级判定（Spec §4 三值）：医保结算退费一律 SETTLED_REFUND（须审批，医保撤销联动 ins_reverse_ref
        //   随 P5 真实通道补齐）；自费按计费日当日/跨日
        RefundType refundType = st.getPayerType() != PayerType.SELF_PAY
                ? RefundType.SETTLED_REFUND
                : (crossDay ? RefundType.CROSS_DAY : RefundType.DAY_CORRECTION);
        // 审批级别判定（Spec `13-billing.md:136` 分级口径）：L0 免审直退落库即 APPROVED；
        //   L1/L2 均先落 PENDING_APPROVAL（一级审批入口），L2 由 approve 在一级批后推进待二级
        ApprovalLevel level = resolveApprovalLevel(refundType, amount);
        boolean autoApproved = level == ApprovalLevel.EXEMPT;
        RefundRequest refund = new RefundRequest();
        refund.setRefundNo("R" + System.nanoTime());
        refund.setSettlementId(st.getId());
        refund.setPatientId(st.getPatientId());
        refund.setVisitId(st.getVisitId());
        refund.setRefundType(refundType);
        refund.setAmount(amount);
        refund.setReason(req.reason());
        refund.setApplicant(applicant);
        refund.setAutoApproved(autoApproved);
        refund.setStatus(autoApproved ? RefundStatus.APPROVED : RefundStatus.PENDING_APPROVAL);
        // 数据库写操作：申请单落库 + link 负向台账逐行落表（同事务；禁 fee_record 负向行——裁决⑫）
        save(refund);
        for (int i = 0; i < req.lines().size(); i++) {
            RefundLine line = req.lines().get(i);
            RefundFeeLink linkRow = new RefundFeeLink();
            linkRow.setRefundId(refund.getId());
            linkRow.setFeeId(line.feeId());
            linkRow.setRefundQuantity(line.refundQuantity());
            linkRow.setRefundAmount(lineAmounts.get(i));
            refundFeeLinkMapper.insert(linkRow);
        }
        if (autoApproved) {
            // 免审直退同事件承载（CF-4）：APPROVED 即发 refund.approved，autoApproved=true 审计检索键
            events.publishEvent(new BillingDomainEvent(
                    BillingMessagingConstants.EVENT_REFUND_APPROVED,
                    new RefundApprovedPayload(
                            refund.getId(),
                            refund.getRefundNo(),
                            refund.getSettlementId(),
                            refund.getPatientId(),
                            refund.getAmount(),
                            refund.getRefundType().getCode(),
                            true)));
        }
        log.info(
                "退费申请落库：refundNo={}，金额={}分，settleNo={}，分级={}，免审直退={}",
                refund.getRefundNo(),
                refund.getAmount(),
                st.getSettleNo(),
                refundType.getCode(),
                autoApproved);
        return refund.getId();
    }

    /**
     * 退费审批（分级两段式：一级批 L2 单推进待二级、L1 单即终批；二级批终批落 APPROVED 并发事件）。
     *
     * <p>执行流程：缺单守卫 → 状态守卫（仅待一级 PENDING_APPROVAL 或待二级 PENDING_SECOND_APPROVAL 可批，
     * 免审直退已 APPROVED 行重复批拒）→ 双人守卫（终批人≠申请人；二级批追加 终批人≠一级审批人，
     * 同一账号不得连批两级）→ 按单据级别与当前状态推进：待一级且级别 L2 → 落一级审批链
     * first_approver/first_approved_at 并置 PENDING_SECOND_APPROVAL（**不发事件**，事件时点=终批，
     * CF-4 `billing.refund.approved` 契约冻结）；待一级且级别 L1 或待二级 → 落终批审批人/时刻、置
     * APPROVED 并发事件（autoApproved 恒 false，载荷与免审区分不变）。
     *
     * <p>角色分权（收费组长一级 / 财务·医保办二级）属 PR-5 RBAC 面：本 PR 以「级别 × 双人链」近似，
     * 待 PR-5 权限点接线后叠加角色硬校验（注释显式声明，禁静默滞留）。
     *
     * @param id 退费申请 id；来源：审批列表选行
     * @throws BizException BILL-1018（404 缺单）/ BILL-1019（409 非待审态）/
     *                      BILL-1020（403 终批人=申请人，或二级终批人=一级审批人，等保分权）
     */
    @Override
    @Transactional
    public void approve(long id) {
        RefundRequest refund = getById(id);
        if (refund == null) {
            throw new BizException(BillingErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND, "退费申请不存在：" + id);
        }
        // 状态守卫：仅待一级/待二级可批（免审直退行已 APPROVED、已终批/已执行行重复批拒）
        if (refund.getStatus() != RefundStatus.PENDING_APPROVAL
                && refund.getStatus() != RefundStatus.PENDING_SECOND_APPROVAL) {
            throw new BizException(
                    BillingErrorCode.REFUND_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "退费申请状态不允许审批，当前状态：" + refund.getStatus().getCode());
        }
        // 双人守卫：登录上下文审批人 ≠ 单据申请人（等保三级分权，红线 M13-03）
        String approver = OperatorContextHolder.get();
        if (approver.equals(refund.getApplicant())) {
            throw new BizException(
                    BillingErrorCode.REFUND_SELF_APPROVAL_FORBIDDEN, HttpStatus.FORBIDDEN, "退费不得自审（审批人须不同于申请人）");
        }
        // 连批守卫（BILL-1020 语义扩展）：二级终批人不得为一级审批人——同一账号不得连批两级，
        //   与申请人自审守卫叠加后申请/一级/二级三方账号两两互异（等保分权链闭合）
        if (refund.getStatus() == RefundStatus.PENDING_SECOND_APPROVAL && approver.equals(refund.getFirstApprover())) {
            throw new BizException(
                    BillingErrorCode.REFUND_SELF_APPROVAL_FORBIDDEN,
                    HttpStatus.FORBIDDEN,
                    "退费二级审批不得连批（二级审批人须不同于一级审批人）");
        }
        if (refund.getStatus() == RefundStatus.PENDING_APPROVAL
                && resolveApprovalLevel(refund.getRefundType(), refund.getAmount()) == ApprovalLevel.SECOND) {
            // 一级审批（L2 单）：状态推进待二级 + 审批链一级留痕（连批守卫比对位）；不发事件（时点=终批）
            refund.setStatus(RefundStatus.PENDING_SECOND_APPROVAL);
            refund.setFirstApprover(approver);
            refund.setFirstApprovedAt(OffsetDateTime.now());
            updateById(refund);
            log.info(
                    "退费一级审批通过（升二级）：refundNo={}，firstApprover={}，金额={}分",
                    refund.getRefundNo(),
                    approver,
                    refund.getAmount());
            return;
        }
        // 终批（L1 一级即终批 / L2 二级终批）：APPROVED 迁移 + 审批人/时刻留痕 + 发事件（同事务）
        refund.setStatus(RefundStatus.APPROVED);
        refund.setApprover(approver);
        refund.setApprovedAt(OffsetDateTime.now());
        updateById(refund);
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：AFTER_COMMIT 经 BillingEventPublisher 出 fy.topic
        events.publishEvent(new BillingDomainEvent(
                BillingMessagingConstants.EVENT_REFUND_APPROVED,
                new RefundApprovedPayload(
                        refund.getId(),
                        refund.getRefundNo(),
                        refund.getSettlementId(),
                        refund.getPatientId(),
                        refund.getAmount(),
                        refund.getRefundType().getCode(),
                        false)));
        log.info("退费审批通过（终批）：refundNo={}，approver={}，金额={}分", refund.getRefundNo(), approver, refund.getAmount());
    }

    /**
     * 退费驳回（PENDING_APPROVAL/PENDING_SECOND_APPROVAL → REJECTED 终态，理由必填留痕）。
     *
     * @param id     退费申请 id；来源：审批列表选行
     * @param reason 驳回理由，非空白；来源：审批人录入（@NotBlank 边界已保，服务层不重复校验）
     * @throws BizException BILL-1018（404 缺单）/ BILL-1019（409 非待审态——已审批单
     *                      走业务逆流程而非驳回）
     */
    @Override
    @Transactional
    public void reject(long id, String reason) {
        RefundRequest refund = getById(id);
        if (refund == null) {
            throw new BizException(BillingErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND, "退费申请不存在：" + id);
        }
        // 状态守卫：待一级/待二级均可驳回（二级驳回语义=一级已批后财务/医保办否决整单；
        //   REJECTED 为终态，已执行单走业务逆流程）
        if (refund.getStatus() != RefundStatus.PENDING_APPROVAL
                && refund.getStatus() != RefundStatus.PENDING_SECOND_APPROVAL) {
            throw new BizException(
                    BillingErrorCode.REFUND_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "退费申请状态不允许驳回，当前状态：" + refund.getStatus().getCode());
        }
        // 数据库写操作：终态迁移 + 驳回理由留痕（同事务）
        refund.setStatus(RefundStatus.REJECTED);
        refund.setRejectReason(reason);
        updateById(refund);
        log.info("退费申请驳回：refundNo={}，rejector={}，reason={}", refund.getRefundNo(), OperatorContextHolder.get(), reason);
    }

    /**
     * 退费执行（APPROVED→EXECUTED，原路退回——资金动作与状态迁移同事务；IT 锚点①终态断言源）。
     *
     * @param id 退费申请 id；来源：审批通过后执行入口
     * @throws BizException BILL-1018（404）/ BILL-1019（409 非 APPROVED）/
     *                      BILL-1012（400 CARD_BALANCE 行卡引用缺失/空文本/非数字——读回侧
     *                      对称守卫，禁裸 parseLong 抛 500 出契约外形态）；
     *                      PAT-1013/1014（卡账户记账失败经调用方事务回滚上抛）
     */
    @Override
    @Transactional
    public void execute(long id) {
        RefundRequest refund = getById(id);
        if (refund == null) {
            throw new BizException(BillingErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND, "退费申请不存在：" + id);
        }
        if (refund.getStatus() != RefundStatus.APPROVED) {
            throw new BizException(
                    BillingErrorCode.REFUND_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅已审批退费可执行，当前状态：" + refund.getStatus().getCode());
        }
        Settlement st = settlementMapper.selectById(refund.getSettlementId());
        // 原路退回·就诊卡侧（2026-09-18 用户裁决：同卡多行聚合单次入账）：自原结算行 payment_details
        //   JSON 读回 CARD_BALANCE 行，channelRef 守卫解析为卡账户 id 后聚合求和去重（与写入侧
        //   「同卡多行求和一笔出账」口径对称——SettlementServiceImpl 两层勾稽段已强制同结算单单卡），
        //   每卡单次全额贷记 refund.getAmount() → 台账 REFUND 入账，回填台账流水 id 作资金溯源锚。
        //   修复前逐行全额贷记：拆分卡支付同卡两行会重复入账两倍退费额；且逐行裸 Long.parseLong
        //   使 channelRef 缺失/null 文本行抛 NumberFormatException 出 500
        Map<Long, Long> cardChannels = new LinkedHashMap<>();
        try {
            for (JsonNode detail : objectMapper.readTree(st.getPaymentDetails())) {
                if ("CARD_BALANCE".equals(detail.path("method").asText())) {
                    // 卡引用先守卫后聚合（2026-09-18 修复）：写入侧 settle 只对 >0 卡行解析引用，
                    //   读回侧不可复制该漏洞——缺失/null/空文本/非数字在此即 BILL-1012 拒，
                    //   杜绝裸 parseLong 抛 NumberFormatException 经兜底渲染成 500 出 BILL-* 契约外
                    cardChannels.merge(
                            parseCardAccountId(detail.path("channelRef")),
                            detail.path("amount").asLong(),
                            Long::sum);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 落库文本解析失败=数据不一致显式暴露（禁静默跳过退回）
            throw new IllegalStateException("payment_details 解析失败，settleNo=" + st.getSettleNo(), e);
        }
        for (Map.Entry<Long, Long> channel : cardChannels.entrySet()) {
            long accountId = channel.getKey();
            long txnId = cardAccountLedger.record(
                    new CardTxnRecord(accountId, CardTxnType.REFUND, refund.getAmount(), refund.getRefundNo()));
            refund.setPaymentRefundRef(String.valueOf(txnId));
            log.info(
                    "退费原路退回入账：refundNo={}，cardAccount={}，卡侧原付合计={}分，本次退={}分",
                    refund.getRefundNo(),
                    accountId,
                    channel.getValue(),
                    refund.getAmount());
        }
        // link 负向台账聚合判态：该行「既有已退 + 本次退」≥ 费用行金额 → FULL_REFUND，否则 PART_REFUND
        //   （负向表达归 refund_fee_link，不生成 fee_record 负向行——裁决⑫同源；本单 link 于 apply 已落表，
        //   聚合口径含 APPROVED 态退费单——本单此刻尚未转 EXECUTED，剔除即永判不满，助手注释锁定）
        for (RefundFeeLink link : refundFeeLinkMapper.selectList(
                Wrappers.<RefundFeeLink>lambdaQuery().eq(RefundFeeLink::getRefundId, refund.getId()))) {
            FeeRecord fee = feeRecordMapper.selectById(link.getFeeId());
            long refunded = refundedFen(fee.getId());
            fee.setStatus(refunded >= fee.getAmount() ? FeeStatus.FULL_REFUND : FeeStatus.PART_REFUND);
            feeRecordMapper.updateById(fee);
        }
        // 结算单全额退完转 REFUNDED（同上聚合口径判定 ≥ 结算总额）；部分退留 SETTLED
        if (totalRefundedFen(st.getId()) >= st.getTotalAmount()) {
            st.setStatus(SettlementStatus.REFUNDED);
            settlementMapper.updateById(st);
        }
        refund.setStatus(RefundStatus.EXECUTED);
        updateById(refund);
        log.info(
                "退费执行完成：refundNo={}，金额={}分，settleNo={}，结算单态={}",
                refund.getRefundNo(),
                refund.getAmount(),
                st.getSettleNo(),
                st.getStatus().getCode());
    }

    /** 退费申请分页查询（status 可空=全部；id 升序=落库行序，A.4.3-17 唯一顺序约束）。 */
    @Override
    @Transactional(readOnly = true)
    public PageResult<RefundRequest> page(RefundStatus status, int page, int size) {
        // 数据库读操作：0 基请求转 MP 1 基 current；status 条件缺席即全状态（审批列表默认视图）
        Page<RefundRequest> result = lambdaQuery()
                .eq(status != null, RefundRequest::getStatus, status)
                .orderByAsc(RefundRequest::getId)
                .page(new Page<>(page + 1, size));
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }

    /**
     * 退费审批级别（Spec `13-billing.md:136` FU-M13-03 分级口径的唯一表达，apply 落初次状态与
     * approve 推进分支共用本判定，禁第二处重复分级）。
     */
    private enum ApprovalLevel {

        /** L0 免审：当日更正 + 无执行占用 + 金额 ≤ autoExemptFen（落库即 APPROVED + 发事件） */
        EXEMPT,

        /** L1 一级：跨日/部分退/超免审但未超一级上限且自费（收费组长一级批即终批） */
        FIRST,

        /** L2 二级：大额（>singleApprovalFen）或医保已结算（财务/医保办终批） */
        SECOND
    }

    /**
     * 退费审批级别判定（Spec `docs/specs/modules/13-billing.md:136`「退费分级（方案 3.3）：当日更正性退费
     * 且未发生执行占用且金额≤阈值 → 收费员免审直退；跨日、超免审阈值、部分退 → 收费组长一级审批；大额
     * （参数阈值）、医保已结算、票据已开具 → 财务/医保办二级审批」）。
     *
     * <p>判定顺序（L2 前置，保守优先）：金额严格大于 {@code singleApprovalFen} 判二级（恰好等于归一级——
     * 与免审「≤ 阈值」的最保守口径一致）；医保已结算退费（{@code refundType=SETTLED_REFUND}，即 apply 侧
     * {@code payerType != SELF_PAY} 的唯一映射，原 :198-200 判定解耦复用）一律二级（基金已支出退费升审）；
     * 其后当日更正且 ≤ {@code autoExemptFen} 判免审（执行占用硬前置已在明细守卫段拦截，命中本方法即无占用）；
     * 其余（跨日/部分退/超免审未超上限且自费）归一级。
     *
     * @param refundType 退费分级（DAY_CORRECTION/CROSS_DAY/SETTLED_REFUND），非空；来源：apply 分级判定
     * @param amount     退费申请金额（分，服务端按明细聚合），非空且 &gt;0
     * @return 审批级别，非空；EXEMPT 免审 / FIRST 一级 / SECOND 二级
     */
    private ApprovalLevel resolveApprovalLevel(RefundType refundType, long amount) {
        // TODO(FU-M13-06): 票据已开具 → 二级。票据维度依赖开票记录（M13 票据管理 FU-M13-06 明确不在
        //   PR-3 范围），本模块当前无法判定开票状态，显式占位禁静默忽略：接入后在此追加票据开具查询即升级。
        // L2 判定：大额（严格大于一级上限，等于归一级）或医保已结算（基金已支出，一律升二级）
        if (amount > properties.singleApprovalFen() || refundType == RefundType.SETTLED_REFUND) {
            return ApprovalLevel.SECOND;
        }
        // L0 免审：当日更正 + 免审阈值内（占用已前置拦截）
        if (refundType == RefundType.DAY_CORRECTION && amount <= properties.autoExemptFen()) {
            return ApprovalLevel.EXEMPT;
        }
        // L1 一级：跨日/部分退/超免审阈值但未超一级上限且自费
        return ApprovalLevel.FIRST;
    }

    /**
     * 读回侧 CARD_BALANCE 行 channelRef → 卡账户 id（与写入侧
     * {@code SettlementServiceImpl.parseCardAccountId} 对称的守卫形态）：JSON 节点缺失/
     * NullNode/空文本/非数字均为资金动作定位要素缺失或非法，BILL-1012 显式拒——禁裸 parseLong
     * 抛 NumberFormatException 经兜底渲染成 500 出 BILL-* 契约外形态（与 parseCardAccountId
     * javadoc 自述契约同源）。
     *
     * @param channelRef payment_details 行内 channelRef 节点；来源：settlement.payment_details
     *                   读回（可空——节点缺失或 JSON null 即非法拒）
     * @return 卡账户 id
     * @throws BizException BILL-1012（400 支付明细行卡引用缺失/非法）
     */
    private static long parseCardAccountId(JsonNode channelRef) {
        // NullNode（"channelRef":null 落库形态）asText() 为字面量 "null"、缺失节点为 ""——
        //   两者唯一区别于显式判定，禁依赖 asText 结果间接兜住
        if (channelRef == null || channelRef.isNull() || channelRef.isMissingNode()) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING,
                    HttpStatus.BAD_REQUEST,
                    "支付明细行卡引用缺失：CARD_BALANCE 行缺少 channelRef");
        }
        String ref = channelRef.asText();
        if (ref.isBlank()) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING,
                    HttpStatus.BAD_REQUEST,
                    "支付明细行卡引用非法：channelRef 为空文本");
        }
        try {
            return Long.parseLong(ref);
        } catch (NumberFormatException e) {
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING,
                    HttpStatus.BAD_REQUEST,
                    "支付明细行卡引用非法：channelRef 必须为卡账户 id：" + ref);
        }
    }

    /**
     * 单费用行累计已退金额（分；link 负向台账聚合）：取 APPROVED/EXECUTED 态退费单 id 集，按集内
     * refund_fee_link 求和——本单于 execute 判定时点已 APPROVED，必须计入（剔除即永判不满）。
     *
     * @param feeId 费用行 id；来源：本单 link 行
     * @return 累计已退金额（分，无历史已退为 0）
     */
    private long refundedFen(long feeId) {
        // 数据库读操作：APPROVED/EXECUTED 态退费单 id 集（免审直退行落库即 APPROVED，天然入集）
        List<Long> refundIds =
                lambdaQuery().in(RefundRequest::getStatus, RefundStatus.APPROVED, RefundStatus.EXECUTED).list().stream()
                        .map(RefundRequest::getId)
                        .toList();
        if (refundIds.isEmpty()) {
            return 0L;
        }
        // 数据库读操作：集内本费用行 link 负向金额求和（禁 XML，两次 lambdaQuery 口径）
        return refundFeeLinkMapper
                .selectList(Wrappers.<RefundFeeLink>lambdaQuery()
                        .eq(RefundFeeLink::getFeeId, feeId)
                        .in(RefundFeeLink::getRefundId, refundIds))
                .stream()
                .mapToLong(RefundFeeLink::getRefundAmount)
                .sum();
    }

    /**
     * 结算单累计已退金额（分；同 {@link #refundedFen(long)} 聚合口径，按结算单维度归集）。
     *
     * @param settlementId 结算单 id；来源：原路退回目标结算行
     * @return 累计已退金额（分，无历史已退为 0）
     */
    private long totalRefundedFen(long settlementId) {
        // 数据库读操作：本结算单 APPROVED/EXECUTED 态退费单 id 集（部分退多次累计口径）
        List<Long> refundIds = lambdaQuery()
                .eq(RefundRequest::getSettlementId, settlementId)
                .in(RefundRequest::getStatus, RefundStatus.APPROVED, RefundStatus.EXECUTED)
                .list()
                .stream()
                .map(RefundRequest::getId)
                .toList();
        if (refundIds.isEmpty()) {
            return 0L;
        }
        // 数据库读操作：集内全部 link 负向金额求和（退费负向表达唯一载体=link 表）
        return refundFeeLinkMapper
                .selectList(Wrappers.<RefundFeeLink>lambdaQuery().in(RefundFeeLink::getRefundId, refundIds))
                .stream()
                .mapToLong(RefundFeeLink::getRefundAmount)
                .sum();
    }
}
