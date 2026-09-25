package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.FeeGenerateCommand;
import com.fuyun.billing.entity.FeeOwnershipSplit;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.FeeSplitType;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.mapper.FeeOwnershipSplitMapper;
import com.fuyun.billing.mapper.FeeRecordMapper;
import com.fuyun.billing.service.IInpatientChargeService;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 住院计费联动服务实现（IInpatientChargeService 唯一实现，装配归 BillingWebConfig @Import）：
 * 六事件业务体——起费锚点/离散计价/费用确认/停费截断/归属切分/停费标记与床位费日切。
 *
 * <p>幂等双层：eventId 构件幂等（IdempotentConsumerSupport 三段式，监听器承载）+ 业务级幂等
 * （锚点/停费标记存在性守卫、床位费与离散计价经计费唯一键 billing_key 数据库硬防重——门诊侧
 * 事件通道同款先例，同来源单据同项目同日唯一）。金额红线：全部费用行金额由计价引擎按种子价/
 * 价格版本服务端计算，事件载荷（仅携 itemCode/quantity 计价要素）不承载金额。
 *
 * <p>线程安全：无状态单例，事务边界逐方法 @Transactional（日切批次逐就诊独立事务）。
 */
@Slf4j
public class InpatientChargeServiceImpl implements IInpatientChargeService {

    /** 床位费项目编码（V1003 种子同源；起费锚点与日切共用计价入口） */
    private static final String BED_ITEM_CODE = "IN_BED_DAY";

    private final FeeOwnershipSplitMapper feeOwnershipSplitMapper;

    private final FeeRecordMapper feeRecordMapper;

    private final IPricingEngineService pricingEngine;

    /**
     * 全参构造器（装配归 BillingWebConfig @Import，backend 宪法 B.1）。
     *
     * @param feeOwnershipSplitMapper 归属切分 mapper，非空；锚点/切分/停费标记落行与日切扫描
     * @param feeRecordMapper         费用行 mapper，非空；执行确认/停嘱截断 CAS
     * @param pricingEngine           计价引擎，非空；床位费与离散计价唯一通道
     */
    public InpatientChargeServiceImpl(
            FeeOwnershipSplitMapper feeOwnershipSplitMapper,
            FeeRecordMapper feeRecordMapper,
            IPricingEngineService pricingEngine) {
        this.feeOwnershipSplitMapper = feeOwnershipSplitMapper;
        this.feeRecordMapper = feeRecordMapper;
        this.pricingEngine = pricingEngine;
    }

    /** {@inheritDoc}：锚点存在性守卫（事件重投零重复行）+ 当日床位费 PENDING 行。 */
    @Override
    @Transactional
    public void onVisitAdmitted(String visitId, long patientId, String wardId, Instant admittedAt) {
        // 数据库读操作：起费锚点幂等守卫（同就诊 ADMIT_START 行唯一，重投/补偿重发跳过）
        boolean anchorExists = lambdaAnchorExists(visitId, FeeSplitType.ADMIT_START);
        if (!anchorExists) {
            FeeOwnershipSplit anchor = new FeeOwnershipSplit();
            anchor.setVisitId(visitId);
            anchor.setPatientId(patientId);
            anchor.setFromWardId(null);
            anchor.setToWardId(wardId);
            anchor.setSplitType(FeeSplitType.ADMIT_START);
            anchor.setSplitAt(utc(admittedAt));
            // 数据库写操作：入科起费锚点落行（fee_ownership_split，归属时间线起点）
            feeOwnershipSplitMapper.insert(anchor);
        }
        chargeBedFee(visitId, patientId, "入科起费");
    }

    /** {@inheritDoc}：逐明细行离散计价（计费唯一键同单同项目同日防重，BILL-1009 幂等跳过）。 */
    @Override
    @Transactional
    public void onOrderCreated(String m04OrderNo, String visitId, long patientId, JsonNode items) {
        for (JsonNode item : items) {
            String itemCode = item.path("itemCode").asText(null);
            JsonNode quantityNode = item.path("quantity");
            if (itemCode == null || itemCode.isBlank() || !(quantityNode.isNumber() || quantityNode.isTextual())) {
                // 计价两要素缺失即不合规帧：显式抛出交容器拒收进 fy.dlx（禁静默零费用）
                throw new IllegalStateException("医嘱开立明细缺 itemCode/quantity 计价要素，无法计价：m04OrderNo=" + m04OrderNo);
            }
            BigDecimal quantity = new BigDecimal(quantityNode.asText());
            FeeGenerateCommand cmd = new FeeGenerateCommand(
                    patientId,
                    visitId,
                    ChargeSource.ORDER_LINKED,
                    m04OrderNo,
                    TriggerType.ORDER_CONFIRMED,
                    itemCode,
                    quantity,
                    VisitType.IN,
                    null,
                    null);
            try {
                long feeId = pricingEngine.generateFromSource(cmd);
                log.info("住院医嘱离散计价：m04OrderNo={}，item={}，quantity={}，feeId={}", m04OrderNo, itemCode, quantity, feeId);
            } catch (BizException e) {
                if (e.getErrorCode() == BillingErrorCode.DUPLICATE_CHARGING) {
                    // 重复计费幂等达成：同单同项目同日已计（重投/补偿重发帧不再进死信）
                    log.warn("住院医嘱重复计费幂等跳过：m04OrderNo={}，item={}", m04OrderNo, itemCode);
                    continue;
                }
                throw e; // 其余业务失败上抛：有界重试耗尽进 fy.dlx 留痕，禁静默丢费
            }
        }
    }

    /** {@inheritDoc}：PENDING→CONFIRMED 直改状态不发事件（brief 冻结语义，fee.confirmed 无调用点）。 */
    @Override
    @Transactional
    public void onOrderExecuted(String m04OrderNo, String visitId) {
        // 数据库写操作：执行回签费用确认（source_ref+visit_id 限定 CAS，重复投递零行幂等）
        int confirmed = feeRecordMapper.casConfirmByOrder(m04OrderNo, visitId);
        log.info("住院医嘱费用确认：m04OrderNo={}，visitId={}，确认行数={}", m04OrderNo, visitId, confirmed);
    }

    /** {@inheritDoc}：未确认 PENDING 行截断作废（已确认/已结算行不回冲）。 */
    @Override
    @Transactional
    public void onOrderStopped(String m04OrderNo, String visitId) {
        // 数据库写操作：停嘱费用截断（PENDING→CANCELLED，部分唯一索引释放计费键占位）
        int cancelled = feeRecordMapper.casCancelPendingByOrder(m04OrderNo, visitId);
        log.info("住院停嘱费用截断：m04OrderNo={}，visitId={}，作废行数={}", m04OrderNo, visitId, cancelled);
    }

    /** {@inheritDoc}：转科切分点落行（时间线记录面，无费用动作）。 */
    @Override
    @Transactional
    public void onVisitTransferred(
            String visitId, long patientId, String fromWardId, String toWardId, Instant transferredAt) {
        FeeOwnershipSplit split = new FeeOwnershipSplit();
        split.setVisitId(visitId);
        split.setPatientId(patientId);
        split.setFromWardId(fromWardId);
        split.setToWardId(toWardId);
        split.setSplitType(FeeSplitType.TRANSFER);
        split.setSplitAt(utc(transferredAt));
        // 数据库写操作：转科归属切分落行（床日费按时间线切分归 P3，本 PR 落切分点记录）
        feeOwnershipSplitMapper.insert(split);
        log.info("住院费用归属切分：visitId={}，{}→{}，splitAt={}", visitId, fromWardId, toWardId, transferredAt);
    }

    /** {@inheritDoc}：停费标记存在性守卫（重复投递零重复行），日切据此跳过出院就诊。 */
    @Override
    @Transactional
    public void onDischargeRequested(String visitId, long patientId, Instant requestedAt) {
        // 数据库读操作：停费标记幂等守卫（同就诊 DISCHARGE_STOP 行唯一）
        if (lambdaAnchorExists(visitId, FeeSplitType.DISCHARGE_STOP)) {
            log.info("出院停费标记已存在（幂等跳过）：visitId={}", visitId);
            return;
        }
        FeeOwnershipSplit stop = new FeeOwnershipSplit();
        stop.setVisitId(visitId);
        stop.setPatientId(patientId);
        stop.setSplitType(FeeSplitType.DISCHARGE_STOP);
        stop.setSplitAt(utc(requestedAt));
        // 数据库写操作：出院停费标记落行（停止持续性计费——日切不再生成床位费）
        feeOwnershipSplitMapper.insert(stop);
    }

    /** {@inheritDoc}：在院人群逐就诊计价（逐就诊独立事务，单点失败不阻断批次）。 */
    @Override
    public int dailyBedCharge() {
        // 数据库读操作：全院在院扫描（有 ADMIT_START 锚点且无 DISCHARGE_STOP 标记）
        List<FeeOwnershipSplit> visits = feeOwnershipSplitMapper.selectDailyChargeableVisits();
        log.info("床位费日切任务开始：在院就诊数={}", visits.size());
        int charged = 0;
        for (FeeOwnershipSplit visit : visits) {
            try {
                if (chargeBedFee(visit.getVisitId(), visit.getPatientId(), "床位费日切")) {
                    charged++;
                }
            } catch (BizException e) {
                // 夜间批次可用性优先：单就诊失败 error 留痕续行，漏费经日志对账补偿
                log.error(
                        "床位费日切单就诊失败：visitId={}，errorCode={}",
                        visit.getVisitId(),
                        e.getErrorCode().getCode(),
                        e);
            }
        }
        log.info("床位费日切任务完成：在院就诊数={}，新生成床位费行数={}", visits.size(), charged);
        return charged;
    }

    /**
     * 当日床位费计价（起费锚点与日切共用入口）：DAY_CUTOVER/DURATION 通道、sourceRef=visitId，
     * 计费唯一键 patient|visit|DURATION|床位项目|计费日 数据库硬防重（visit×日天然幂等）。
     *
     * @param visitId   住院就诊号，非空
     * @param patientId 患者主索引，非空
     * @param scene     业务场景标识（日志锚点），非空
     * @return true=新生成；false=当日已计费（幂等跳过）
     */
    private boolean chargeBedFee(String visitId, long patientId, String scene) {
        try {
            pricingEngine.generateFromSource(new FeeGenerateCommand(
                    patientId,
                    visitId,
                    ChargeSource.DAY_CUTOVER,
                    visitId,
                    TriggerType.DURATION,
                    BED_ITEM_CODE,
                    BigDecimal.ONE,
                    VisitType.IN,
                    null,
                    null));
            log.info("{}床位费生成：visitId={}，item={}", scene, visitId, BED_ITEM_CODE);
            return true;
        } catch (BizException e) {
            if (e.getErrorCode() == BillingErrorCode.DUPLICATE_CHARGING) {
                // 计费唯一键命中=当日床位费已在（入科当日与 02:30 日切重叠面/任务重跑幂等）
                log.info("{}床位费当日已计（幂等跳过）：visitId={}", scene, visitId);
                return false;
            }
            throw e;
        }
    }

    /**
     * 同类型切分行存在性守卫（锚点/停费标记的唯一性承载——事件重投与补偿重发幂等）。
     *
     * @param visitId 住院就诊号，非空
     * @param type    目标切分类型，非空
     * @return true=已存在同类型行（跳过落行）
     */
    private boolean lambdaAnchorExists(String visitId, FeeSplitType type) {
        // 数据库读操作：visit_id+split_type 存在性反查（注解 SQL 不在本面，wrapper 链式承载）
        Long count = feeOwnershipSplitMapper.selectCount(Wrappers.<FeeOwnershipSplit>lambdaQuery()
                .eq(FeeOwnershipSplit::getVisitId, visitId)
                .eq(FeeOwnershipSplit::getSplitType, type));
        return count != null && count > 0;
    }

    /**
     * 事件载荷时点（Instant UTC）转 TIMESTAMPTZ 实体承载（split_at 列 OffsetDateTime 形态，
     * UTC 收口单点禁散落裸构造）。
     *
     * @param instant 事件载荷时点，非空
     * @return UTC OffsetDateTime，非空
     */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
