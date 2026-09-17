package com.fuyun.billing.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.ChargeItemPricePublishedPayload;
import com.fuyun.billing.constants.BillingMessagingConstants;
import com.fuyun.billing.dto.PriceDraftRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.entity.ChargeItemPrice;
import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.PriceSource;
import com.fuyun.billing.enums.PriceStatus;
import com.fuyun.billing.internal.BillingDomainEvent;
import com.fuyun.billing.mapper.ChargeItemPriceMapper;
import com.fuyun.billing.record.PriceSnapshot;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.billing.service.IChargePriceService;
import com.fuyun.billing.service.IInsuranceMappingService;
import com.fuyun.common.exception.BizException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 价格版本化管理（billing.charge_item_price，方案 3.4 版本化价格 + 计费快照）。
 *
 * <p>快照冻结红线：调价不溯既往——已生成费用行按当时 unit_price_snapshot/price_version/目录版本
 * 重现，本表版本区间为「新费用取价」唯一权威源；「调价草稿→定时生效→广播」三步闭环。
 * 事务边界在 publish（闭旧 + 置新同事务）；广播发布点在事务内 publishEvent(BillingDomainEvent)，
 * 经 BillingEventPublisher AFTER_COMMIT 出 MQ（A.4.2-7 禁事务内发 MQ；回滚不误广播）。
 */
@Slf4j
public class ChargePriceServiceImpl extends ServiceImpl<ChargeItemPriceMapper, ChargeItemPrice>
        implements IChargePriceService {

    private final IInsuranceMappingService mappingService;

    private final IChargeItemService itemService;

    private final ApplicationEventPublisher events;

    /** 全参构造器（装配归 BillingWebConfig @Import）。 */
    public ChargePriceServiceImpl(
            IInsuranceMappingService mappingService, IChargeItemService itemService, ApplicationEventPublisher events) {
        this.mappingService = mappingService;
        this.itemService = itemService;
        this.events = events;
    }

    /**
     * 取项目当前生效价格 + 医保对照冻结为计费快照（计价引擎/结算取价唯一入口）。
     *
     * @param itemCode     项目编码（日志锚点）
     * @param chargeItemId 项目 id
     * @return 价格快照，非空
     * @throws BizException BILL-1008（409 无生效价格版本）
     */
    @Override
    @Transactional(readOnly = true)
    public PriceSnapshot snapshot(String itemCode, long chargeItemId) {
        // 数据库读操作：当前生效版本（status=PUBLISHED 且 effective_to IS NULL，部分唯一索引保证至多一行）
        ChargeItemPrice current = lambdaQuery()
                .eq(ChargeItemPrice::getChargeItemId, chargeItemId)
                .eq(ChargeItemPrice::getStatus, PriceStatus.PUBLISHED)
                .isNull(ChargeItemPrice::getEffectiveTo)
                .one();
        if (current == null) {
            throw new BizException(BillingErrorCode.PRICING_UNAVAILABLE, HttpStatus.CONFLICT, "项目无生效价格版本：" + itemCode);
        }
        InsuranceMapping mapping = mappingService.effectiveMapping(chargeItemId);
        return new PriceSnapshot(
                chargeItemId,
                current.getPrice(),
                current.getVersion(),
                mapping == null ? null : mapping.getNhsaCode(),
                mapping == null ? null : mapping.getCatalogVersion(),
                mapping == null ? null : mapping.getSelfPayRatio(),
                mapping == null ? null : mapping.getLimitPrice());
    }

    /**
     * 调价草稿落库（版本号项目内自增，不触发生效——生效须显式 publish）。
     *
     * @param req 草稿请求，非空
     * @return 草稿行 id
     */
    @Override
    @Transactional
    public long saveDraft(PriceDraftRequest req) {
        ChargeItem item = itemService.requireActiveByCode(req.itemCode());
        Integer maxVersion = lambdaQuery()
                .eq(ChargeItemPrice::getChargeItemId, item.getId())
                .orderByDesc(ChargeItemPrice::getVersion)
                .last("LIMIT 1")
                .oneOpt()
                .map(ChargeItemPrice::getVersion)
                .orElse(0);
        ChargeItemPrice draft = new ChargeItemPrice();
        draft.setChargeItemId(item.getId());
        draft.setPrice(req.price());
        draft.setVersion(maxVersion + 1);
        draft.setEffectiveFrom(req.effectiveFrom());
        draft.setPriceSource(req.priceSource() == null ? PriceSource.OFFICIAL_DOC : req.priceSource());
        draft.setApprovalNo(req.approvalNo());
        draft.setStatus(PriceStatus.DRAFT);
        save(draft);
        log.info("调价草稿落库：itemId={}，version={}，price={}分", item.getId(), draft.getVersion(), req.price());
        return draft.getId();
    }

    /**
     * 发布调价（DRAFT→PUBLISHED，闭当前生效版本区间，广播工作站刷新）。
     *
     * @param priceId 草稿行 id
     * @throws BizException BILL-1005（404 缺行）/ BILL-1028（409 非 DRAFT 重复发布）
     */
    @Override
    @Transactional
    public void publish(long priceId) {
        ChargeItemPrice target = getById(priceId);
        if (target == null) {
            throw new BizException(BillingErrorCode.PRICE_NOT_FOUND, HttpStatus.NOT_FOUND, "价格版本不存在");
        }
        if (target.getStatus() != PriceStatus.DRAFT) {
            throw new BizException(BillingErrorCode.PRICE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅 DRAFT 版本可发布");
        }
        // 数据库写操作：闭旧生效版本 effective_to（区间不重叠——uk_price_item_current 保证当前唯一）
        ChargeItemPrice current = lambdaQuery()
                .eq(ChargeItemPrice::getChargeItemId, target.getChargeItemId())
                .eq(ChargeItemPrice::getStatus, PriceStatus.PUBLISHED)
                .isNull(ChargeItemPrice::getEffectiveTo)
                .one();
        // TODO(P1-后段): 区间不重叠守卫——target.effectiveFrom < current.effectiveFrom 时拒 BILL-1004
        //   （现仅闭旧不校验新起点历史倒挂场景，PR-3 演示不触发；uk_price_item_current 兜底当前唯一）
        if (current != null) {
            current.setEffectiveTo(target.getEffectiveFrom());
            current.setStatus(PriceStatus.EXPIRED);
            updateById(current);
        }
        target.setStatus(PriceStatus.PUBLISHED);
        updateById(target);
        // 事务内仅发应用事件（A.4.2-7 禁事务内发 MQ）：AFTER_COMMIT 由 BillingEventPublisher 出 fy.topic，
        //   事务回滚则广播不出（调价未生效不误刷工作站缓存）
        ChargeItem item = itemService.getById(target.getChargeItemId());
        events.publishEvent(new BillingDomainEvent(
                BillingMessagingConstants.EVENT_PRICE_PUBLISHED,
                new ChargeItemPricePublishedPayload(
                        target.getChargeItemId(),
                        item.getItemCode(),
                        target.getVersion(),
                        target.getPrice(),
                        target.getEffectiveFrom().toInstant())));
        log.info(
                "调价发布：itemId={}，version={}，生效={}",
                target.getChargeItemId(),
                target.getVersion(),
                target.getEffectiveFrom());
    }

    /** 列项目价格版本链（管理面版本历史查询，version 倒序）。 */
    @Override
    @Transactional(readOnly = true)
    public List<ChargeItemPrice> listVersions(long chargeItemId) {
        // 数据库读操作：版本历史（version 倒序，含 DRAFT/EXPIRED 全状态供管理面追溯）
        return lambdaQuery()
                .eq(ChargeItemPrice::getChargeItemId, chargeItemId)
                .orderByDesc(ChargeItemPrice::getVersion)
                .list();
    }
}
