package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.FeeCreatedPayload;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.dto.ManualChargeRequest;
import com.fuyun.billing.dto.QuoteRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemComponent;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.record.PriceSnapshot;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.billing.service.IChargePriceService;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.billing.vo.QuoteVO;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.VisitIdValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计价引擎（M13 §3.1 事件驱动 + 快照冻结；billing.fee_record 生成唯一入口）。
 *
 * <p>硬规则：①金额一律服务端计算（单价快照 × 数量，HALF_UP 取整到分），入参无金额字段；
 * ②billing_key（患者+来源单据+计费点+项目+计费日）唯一约束兜底防重复计费（调研依据 3，
 * 患者维度入键防手工通道操作者工号复用致跨患者误拦）；
 * ③快照列冻结当下生效价与目录版本（调价不溯既往）。手工计费与事件生成同经 generateFromSource，
 * 差异仅在 charge_source 与 sourceRef 取值（手工=操作者工号，manualCharge 薄编排注入上下文）。
 * ④费用生成事件经 ApplicationEventPublisher + BillingEventPublisher AFTER_COMMIT 出 MQ（禁事务内发 MQ）。
 * ⑤组合项目（combo_flag）计价时按构成展开：逐成员一行、数量=入参×构成默认量，预览与实收同源
 * （FU-M13-01，2026-09-17 审查裁决补实现）；未结算作废 cancel 置 CANCELLED，部分唯一索引即释放
 * billing_key 占位（行保留审计，禁物理删）。
 */
@Slf4j
public class PricingEngineServiceImpl extends ServiceImpl<FeeRecordMapper, FeeRecord> implements IPricingEngineService {

    private final IChargeItemService itemService;

    private final IChargePriceService priceService;

    private final ApplicationEventPublisher events;

    /** 全参构造器（装配归 BillingWebConfig @Import）。 */
    public PricingEngineServiceImpl(
            IChargeItemService itemService, IChargePriceService priceService, ApplicationEventPublisher events) {
        this.itemService = itemService;
        this.priceService = priceService;
        this.events = events;
    }

    /**
     * 生成待收费费用（事件驱动与手工计费统一入口，调用方事务内执行）。
     *
     * <p>组合项目展开（FU-M13-01 方案 3.4「划价按构成展开成员明细、逐成员计价与医保对照」，
     * 2026-09-17 审查裁决=补实现）：combo_flag=TRUE 时经 {@code itemService.listComponents} 逐成员
     * 生效校验后各生成一条费用——成员行独立取价/对照/billing_key/发布 fee.created，成员数量=入参
     * 数量×构成 default_quantity（DECIMAL 精确乘算）；返回首条成员行 id 作命令回执（全量行以
     * pendingFees/费用查询为准）。非组合走单行原语义。
     *
     * @param cmd 费用生成命令，非空
     * @return 新费用行 id（组合展开多行时为首成员行 id）
     * @throws BizException BILL-1013（400 visit_id 结构不合法，事件消费/手工两通道共同入口守卫）/
     *                      BILL-1009（409 重复计费）/ BILL-1008（定价不可得，经 snapshot 抛；
     *                      组合未维护构成同码显式拒）/ BILL-1001（组合成员缺行脏数据）
     */
    @Override
    @Transactional
    public long generateFromSource(FeeGenerateCommand cmd) {
        // CF-3 结构守卫：非法就诊号在计费落库前拒（BILL-1013 引用点收口，消费侧不可信输入面）
        if (!VisitIdValidator.isValid(cmd.visitId())) {
            throw new BizException(
                    BillingErrorCode.VISIT_ID_MALFORMED, HttpStatus.BAD_REQUEST, "就诊号结构不合法：" + cmd.visitId());
        }
        ChargeItem item = itemService.requireActiveByCode(cmd.itemCode());
        if (!Boolean.TRUE.equals(item.getComboFlag())) {
            return chargeOne(cmd, item);
        }
        // 数据库读操作：组合构成清单（V600 charge_item_component）——未维护构成即定价不可得，禁静默零费用
        List<ChargeItemComponent> components = itemService.listComponents(item.getId());
        if (components.isEmpty()) {
            throw new BizException(
                    BillingErrorCode.PRICING_UNAVAILABLE, HttpStatus.CONFLICT, "组合项目未维护构成：" + cmd.itemCode());
        }
        long firstFeeId = 0L;
        for (ChargeItemComponent component : components) {
            ChargeItem member = memberItem(component.getComponentItemId());
            // 成员命令仅换计价项目与数量（患者/来源单据/触发型/就诊型/手工要素沿用入参命令）
            long feeId = chargeOne(
                    withMember(cmd, member.getItemCode(), cmd.quantity().multiply(component.getDefaultQuantity())),
                    member);
            if (firstFeeId == 0L) {
                firstFeeId = feeId;
            }
        }
        log.info("组合项目展开计价：comboItem={}，成员行数={}", cmd.itemCode(), components.size());
        return firstFeeId;
    }

    /** 成员命令派生：record 全组件复制仅替换项目编码与数量（A.1-2 参数对象化，禁原地改入参命令）。 */
    private FeeGenerateCommand withMember(FeeGenerateCommand cmd, String memberCode, BigDecimal quantity) {
        return new FeeGenerateCommand(
                cmd.patientId(),
                cmd.visitId(),
                cmd.source(),
                cmd.sourceRef(),
                cmd.trigger(),
                memberCode,
                quantity,
                cmd.visitType(),
                cmd.operator(),
                cmd.manualReason());
    }

    /**
     * 组合成员生效校验：按成员 id 取编码后复用按码生效守卫——缺行 BILL-1001、停用 BILL-1003 与
     * 事件通道同口径（构成脏数据显式暴露，禁 getById 空引用静默 NPE）。
     *
     * @param componentItemId 成员项目 id；来源：charge_item_component 行
     * @return ACTIVE 成员项目实体，非空
     * @throws BizException BILL-1001（成员缺行）/ BILL-1003（成员停用）
     */
    private ChargeItem memberItem(long componentItemId) {
        ChargeItem member = itemService.getById(componentItemId);
        if (member == null) {
            throw new BizException(
                    BillingErrorCode.CHARGE_ITEM_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    "组合成员项目不存在（构成脏数据）：" + componentItemId);
        }
        return itemService.requireActiveByCode(member.getItemCode());
    }

    /**
     * 单行计价落库（generateFromSource 非组合原语，组合展开逐成员复用）：取价快照→billing_key
     * 防重查重→金额服务端算→手工要素守卫→落 PENDING 行→事务内发 fee.created 应用事件。
     *
     * @param cmd  费用生成命令（itemCode 已定位到本行项目），非空
     * @param item cmd.itemCode 对应生效项目，非空
     * @return 新费用行 id
     */
    private long chargeOne(FeeGenerateCommand cmd, ChargeItem item) {
        PriceSnapshot snap = priceService.snapshot(cmd.itemCode(), item.getId());
        LocalDate billingDate = LocalDate.now();
        // 唯一键五段（患者维度入键，见 V602 注释）：long 拼接走字符串化，无金额语义
        String billingKey = cmd.patientId() + "|" + cmd.sourceRef() + "|"
                + cmd.trigger().getCode() + "|" + item.getId() + "|" + billingDate;
        // 数据库读操作：计费唯一键查重（billing_key 四要素，防同单同项目同日重复计费）
        boolean duplicated =
                lambdaQuery().eq(FeeRecord::getBillingKey, billingKey).exists();
        if (duplicated) {
            log.warn("重复计费拦截：billingKey={}，visit={}", billingKey, cmd.visitId());
            throw new BizException(BillingErrorCode.DUPLICATE_CHARGING, HttpStatus.CONFLICT, "该来源单据同项目同日已计费，避免重复收费");
        }
        // 金额服务端算（红线 1/D5）：单价快照 × 数量 HALF_UP 取整到分，入参无金额字段
        long amount = BigDecimal.valueOf(snap.unitPrice())
                .multiply(cmd.quantity())
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        // 手工计费红线 3：操作者与理由必填（事件驱动路径二者为 null 合法）
        if (cmd.source() == ChargeSource.MANUAL) {
            if (cmd.operator() == null
                    || cmd.operator().isBlank()
                    || cmd.manualReason() == null
                    || cmd.manualReason().isBlank()) {
                throw new BizException(
                        BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING, HttpStatus.BAD_REQUEST, "手工计费必须记录操作者与理由");
            }
        }
        FeeRecord fee = new FeeRecord();
        fee.setOperator(cmd.operator());
        fee.setManualReason(cmd.manualReason());
        fee.setFeeNo(genFeeNo());
        fee.setPatientId(cmd.patientId());
        fee.setVisitId(cmd.visitId());
        fee.setVisitType(cmd.visitType());
        fee.setChargeItemId(item.getId());
        fee.setItemNameSnapshot(item.getItemName());
        fee.setUnitPriceSnapshot(snap.unitPrice());
        fee.setQuantity(cmd.quantity());
        fee.setAmount(amount);
        fee.setFeeCategorySnapshot(item.getFeeCategory());
        fee.setChargeSource(cmd.source());
        fee.setSourceRef(cmd.sourceRef());
        fee.setTriggerPoint(cmd.trigger()); // 枚举直存（DB 列经 @EnumValue 写 code，与快照口径一致）
        fee.setBillingDate(billingDate);
        fee.setBillingKey(billingKey);
        fee.setNhsaCodeSnapshot(snap.nhsaCode());
        fee.setCatalogVersionSnapshot(snap.catalogVersion());
        fee.setSelfPayRatioSnapshot(snap.selfPayRatio());
        fee.setLimitPriceSnapshot(snap.limitPrice());
        fee.setPriceVersion(snap.priceVersion());
        fee.setStatus(FeeStatus.PENDING);
        save(fee);
        // 事务内发应用事件（A.4.2-7 禁事务内直发 MQ）：AFTER_COMMIT 经 BillingEventPublisher 出 fy.topic
        events.publishEvent(new BillingDomainEvent(
                BillingMessagingConstants.EVENT_FEE_CREATED,
                new FeeCreatedPayload(
                        fee.getId(),
                        fee.getFeeNo(),
                        fee.getPatientId(),
                        fee.getVisitId(),
                        fee.getChargeItemId(),
                        fee.getItemNameSnapshot(),
                        fee.getAmount(),
                        fee.getChargeSource().getCode(),
                        billingKey)));
        log.info(
                "费用生成：feeNo={}，visit={}，item={}，amount={}分，source={}",
                fee.getFeeNo(),
                cmd.visitId(),
                cmd.itemCode(),
                amount,
                cmd.source());
        return fee.getId();
    }

    /** 简易费用编号生成（雪花后缀；生产可切业务序列，PR-3 演示足够，非资金键）。 */
    private String genFeeNo() {
        return "F" + System.nanoTime();
    }

    /** 就诊未结算 PENDING 费用清单（结算前勾稽 + 已收费用回显）。 */
    @Override
    @Transactional(readOnly = true)
    public List<FeeRecord> pendingFees(String visitId) {
        // 数据库读操作：按就诊号过滤 PENDING 状态行（已结算/作废行不参与勾稽）
        return lambdaQuery()
                .eq(FeeRecord::getVisitId, visitId)
                .eq(FeeRecord::getStatus, FeeStatus.PENDING)
                .list();
    }

    /** 就诊费用分页查询（工作站 GET /fees 消费；全状态、id 升序=事件行序，A.4.3-17 唯一顺序约束）。 */
    @Override
    @Transactional(readOnly = true)
    public PageResult<FeeRecord> pageByVisit(String visitId, int page, int size) {
        // 数据库读操作：0 基请求转 MP 1 基 current；排序锚定主键保证行序稳定（禁无序分页）
        Page<FeeRecord> result = lambdaQuery()
                .eq(FeeRecord::getVisitId, visitId)
                .orderByAsc(FeeRecord::getId)
                .page(new Page<>(page + 1, size));
        return PageResult.of(result.getRecords(), page, size, result.getTotal());
    }

    /** 预计价（只算不落、不发消息，供划价界面展示；无对照行显式标自费；组合行与实收同源展开）。 */
    @Override
    @Transactional(readOnly = true)
    public QuoteVO quote(QuoteRequest req) {
        List<QuoteVO.Line> lines = new ArrayList<>();
        long total = 0L;
        for (QuoteRequest.Line l : req.lines()) {
            ChargeItem item = itemService.requireActiveByCode(l.itemCode());
            if (Boolean.TRUE.equals(item.getComboFlag())) {
                // 组合预计价与计价引擎展开同源（FU-M13-01）：逐成员行、数量=行数量×构成默认量，
                //   杜绝「预览一行、实收多行」的口径分裂；未维护构成守卫与 generateFromSource 同源
                //   （第 2 轮审查 P2-5：禁预览出空行合计 0 的静默假价）
                List<ChargeItemComponent> components = itemService.listComponents(item.getId());
                if (components.isEmpty()) {
                    throw new BizException(
                            BillingErrorCode.PRICING_UNAVAILABLE, HttpStatus.CONFLICT, "组合项目未维护构成：" + l.itemCode());
                }
                for (ChargeItemComponent component : components) {
                    ChargeItem member = memberItem(component.getComponentItemId());
                    total += quoteLine(lines, member, l.quantity().multiply(component.getDefaultQuantity()));
                }
                continue;
            }
            total += quoteLine(lines, item, l.quantity());
        }
        return new QuoteVO(req.visitId(), total, lines);
    }

    /** 单行预计价：快照取价 + 服务端算额 + 行装配，返回行金额（分）。 */
    private long quoteLine(List<QuoteVO.Line> lines, ChargeItem item, BigDecimal quantity) {
        PriceSnapshot snap = priceService.snapshot(item.getItemCode(), item.getId());
        long amount = BigDecimal.valueOf(snap.unitPrice())
                .multiply(quantity)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
        lines.add(new QuoteVO.Line(
                item.getId(),
                item.getItemCode(),
                item.getItemName(),
                snap.unitPrice(),
                quantity,
                amount,
                snap.nhsaCode() == null)); // selfExpenseOnly：无对照=true→仅自费提示
        return amount;
    }

    /**
     * 费用作废（PENDING/CONFIRMED→CANCELLED，未结算更正通道）。
     *
     * <p>billing_key 释放语义（V602 部分唯一索引同源）：行逻辑保留、仅置状态，禁物理删——
     * {@code WHERE deleted = 0 AND status <> 'CANCELLED'} 使作废行不再占键，同来源单据同项目
     * 当日可重新计费更正；作废理由经 controller @AuditLog 审计留痕（fee_record 不另建立由列）。
     * 状态谓词并入 UPDATE 条件（D-13 收口同型：读-写窗口内被并发结算则 0 行命中显式拒，禁误作废）。
     *
     * @param feeId  费用行 id；来源：工作站费用查询选行
     * @param reason 作废理由，非空白；来源：操作者录入（仅作日志/审计锚点，不改写不另存列）
     * @throws BizException BILL-1010（404 费用不存在）/ BILL-1011（409 非 PENDING/CONFIRMED 态或状态竞态）
     */
    @Override
    @Transactional
    public void cancel(long feeId, String reason) {
        FeeRecord fee = getById(feeId);
        if (fee == null) {
            throw new BizException(BillingErrorCode.FEE_NOT_FOUND, HttpStatus.NOT_FOUND, "费用记录不存在：" + feeId);
        }
        if (fee.getStatus() != FeeStatus.PENDING && fee.getStatus() != FeeStatus.CONFIRMED) {
            throw new BizException(
                    BillingErrorCode.FEE_STATE_NOT_ALLOWED,
                    HttpStatus.CONFLICT,
                    "仅未结算费用可作废，当前状态：" + fee.getStatus().getCode());
        }
        // 数据库写操作：条件更新仅置状态列，WHERE 携带原状态谓词（并发结算竞态守卫）
        boolean cancelled = lambdaUpdate()
                .set(FeeRecord::getStatus, FeeStatus.CANCELLED)
                .eq(FeeRecord::getId, feeId)
                .in(FeeRecord::getStatus, FeeStatus.PENDING, FeeStatus.CONFIRMED)
                .update();
        if (!cancelled) {
            // 读-写窗口内被并发结算/再作废：0 行命中统一按状态不允许拒（禁静默假成功）
            throw new BizException(BillingErrorCode.FEE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "费用状态竞态，作废失败");
        }
        log.info("费用作废：feeId={}，原状态={}，reason={}", feeId, fee.getStatus().getCode(), reason);
    }

    /**
     * 手工计费（FU-M13-02 手工通道服务薄编排）：操作者恒取登录上下文（红线 3，不由前端传），
     * 组装命令委托 generateFromSource 统一入口（组合展开/防重/快照/事件全部复用，无第二套计价路径）。
     *
     * @param req 手工计费请求，非空；reason 来源：收费员补录理由
     * @return 新费用行 id（组合展开时为首成员行 id）
     * @throws BizException BILL-1012（400 无登录操作者上下文——前置拒，引擎守卫双保险）；
     *                      余同 generateFromSource
     */
    @Override
    @Transactional
    public long manualCharge(ManualChargeRequest req) {
        String operator = OperatorContextHolder.get();
        if (operator == null || operator.isBlank()) {
            // 手工通道身份红线：无登录上下文不落不可追溯费用（审计断链即资金不可追）
            throw new BizException(
                    BillingErrorCode.MANUAL_CHARGE_CONTEXT_MISSING, HttpStatus.BAD_REQUEST, "缺少登录操作者上下文，禁止手工计费");
        }
        String visitId = req.visitId();
        // 就诊类型按 CF-3 前缀派生（I=住院，其余含非法形态按门诊出参、非法结构由引擎守卫 BILL-1013 拒）
        VisitType visitType =
                visitId != null && visitId.startsWith(VisitIdValidator.TYPE_INPATIENT) ? VisitType.IN : VisitType.OUT;
        return generateFromSource(new FeeGenerateCommand(
                req.patientId(),
                visitId,
                ChargeSource.MANUAL,
                operator,
                TriggerType.MANUAL,
                req.itemCode(),
                req.quantity(),
                visitType,
                operator,
                req.reason()));
    }
}
