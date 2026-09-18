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
import java.time.Clock;
import java.time.OffsetDateTime;
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
 * 其中「定时生效」由取价侧区间判定承接：snapshot 以注入时钟求 now 判定半开区间
 * [effective_from, effective_to)，到点自然切换，无调度器/延迟队列/二次激活动作；广播在发布时点
 * 发出、载荷携生效时刻 effectiveFrom（工作站据此到点刷新本地缓存）。
 * 事务边界在 publish（闭旧 + 置新同事务）；广播发布点在事务内 publishEvent(BillingDomainEvent)，
 * 经 BillingEventPublisher AFTER_COMMIT 出 MQ（A.4.2-7 禁事务内发 MQ；回滚不误广播）。
 */
@Slf4j
public class ChargePriceServiceImpl extends ServiceImpl<ChargeItemPriceMapper, ChargeItemPrice>
        implements IChargePriceService {

    private final IInsuranceMappingService mappingService;

    private final IChargeItemService itemService;

    private final ApplicationEventPublisher events;

    /**
     * 取价时刻时钟（价格区间判定基准）：装配期由 BillingWebConfig 注入 UTC 系统时钟
     * （Clock.systemUTC()，SystemWebConfig 同款形态，不注册全局 Clock Bean 以免与 IotAmqpConfig
     * 条件装配的 iotAmqpClock 形成类型注入歧义）；UTC 偏移与 TIMESTAMPTZ 列的瞬时语义、
     * pgjdbc 的 OffsetDateTime 绑定同源，不依赖 JVM 默认时区（容器 Asia/Shanghai）与库会话时区。
     * 单测以固定/可推进时钟注入，确定性覆盖「到点前取旧版本 / 到点后取新版本」。
     */
    private final Clock clock;

    /**
     * 全参构造器（装配归 BillingWebConfig @Bean 显式构造——唯一需注入时间源的 service）。
     *
     * @param mappingService 医保对照服务，非空；来源：容器 Bean
     * @param itemService    收费项目服务，非空；来源：容器 Bean
     * @param events         应用事件发布器，非空；来源：容器 Bean（事务内发事件，AFTER_COMMIT 出 MQ）
     * @param clock          取价时刻时钟，非空；来源：装配期 Clock.systemUTC() 或单测固定时钟
     */
    public ChargePriceServiceImpl(
            IInsuranceMappingService mappingService,
            IChargeItemService itemService,
            ApplicationEventPublisher events,
            Clock clock) {
        this.mappingService = mappingService;
        this.itemService = itemService;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 取项目当前生效价格 + 医保对照冻结为计费快照（计价引擎/结算取价唯一入口）。
     *
     * <p>生效判定＝取价侧时间区间判定（定时生效不依赖调度器）：版本区间为半开
     * {@code [effective_from, effective_to)}，以注入时钟求 now，命中
     * {@code status <> DRAFT AND effective_from <= now AND (effective_to IS NULL OR effective_to > now)}，
     * 并按 effective_from 倒序取首行——区间不重叠由 publish 守卫与 uk_price_item_current（项目至多
     * 一条未闭 PUBLISHED）共同保证，首行即当前时刻唯一命中行。由此：发布未来起点版本后，旧版本在
     * 到点前继续按旧价供新费用取价，新版本到点自然生效。
     *
     * <p>EXPIRED 语义澄清（枚举 javadoc 与 V600 迁移措辞按已应用产物口径保留）：EXPIRED 表示区间已闭
     * （effective_to 落点），其历史区间在未到闭点前仍可被本时间判定命中——发布未来起点版本后旧行即
     * 此形态（未到闭点前仍生效），故查询以 status <> DRAFT 而非 status = PUBLISHED 过滤。
     *
     * @param itemCode     项目编码（日志锚点）
     * @param chargeItemId 项目 id
     * @return 价格快照，非空
     * @throws BizException BILL-1008（409 无生效价格版本：各版本区间均未覆盖当前时刻）
     */
    @Override
    @Transactional(readOnly = true)
    public PriceSnapshot snapshot(String itemCode, long chargeItemId) {
        // 取价时刻：注入时钟求 now（区间判定基准，UTC 瞬时；见 clock 字段说明）
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 数据库读操作：时间区间判定取当前生效版本（status <> DRAFT 排除草稿；DESC + LIMIT 1 取
        //   当前时刻命中行——区间不重叠前提下唯一，脏数据（区间交叉）时取起点最新行定序）
        ChargeItemPrice current = lambdaQuery()
                .eq(ChargeItemPrice::getChargeItemId, chargeItemId)
                .ne(ChargeItemPrice::getStatus, PriceStatus.DRAFT)
                .le(ChargeItemPrice::getEffectiveFrom, now)
                .and(w -> w.isNull(ChargeItemPrice::getEffectiveTo).or().gt(ChargeItemPrice::getEffectiveTo, now))
                .orderByDesc(ChargeItemPrice::getEffectiveFrom)
                .last("LIMIT 1")
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
     * 发布调价（DRAFT→PUBLISHED，闭当前未闭版本区间并置新起点，广播工作站刷新）。
     *
     * <p>广播时点＝发布时点，载荷携生效时刻 effectiveFrom（工作站据此在到点时刻刷新本地价格缓存，
     * 消解「定时生效→广播」的字面歧义：广播不等到点，到点由取价侧区间判定承接，见 {@link #snapshot}）。
     * 闭旧＝当前未闭行 effective_to 落到新起点并置 EXPIRED：新起点在未来时旧行区间仍覆盖当前时刻，
     * 取价继续命中旧行，直到新起点到点后由区间判定自动切换。
     *
     * @param priceId 草稿行 id
     * @throws BizException BILL-1005（404 缺行）/ BILL-1028（409 非 DRAFT 重复发布）
     *                     / BILL-1004（409 新起点早于当前版本起点：区间倒挂/溯及既往拒发布）
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
        // 数据库读操作：闭旧生效版本 effective_to（区间不重叠——uk_price_item_current 保证当前唯一）
        ChargeItemPrice current = lambdaQuery()
                .eq(ChargeItemPrice::getChargeItemId, target.getChargeItemId())
                .eq(ChargeItemPrice::getStatus, PriceStatus.PUBLISHED)
                .isNull(ChargeItemPrice::getEffectiveTo)
                .one();
        // 区间不重叠守卫（Spec §3.4「调价不溯既往」）：新起点早于当前未闭行起点即区间倒挂/溯及既往，
        //   闭旧会把当前区间写成负长度并让时间判定失效，先行拒绝（BILL-1004）且不落任何写
        if (current != null && target.getEffectiveFrom().isBefore(current.getEffectiveFrom())) {
            throw new BizException(BillingErrorCode.PRICE_RANGE_CONFLICT, HttpStatus.CONFLICT, "新版本生效起点早于当前版本：调价不溯既往");
        }
        if (current != null) {
            current.setEffectiveTo(target.getEffectiveFrom());
            current.setStatus(PriceStatus.EXPIRED);
            updateById(current);
        }
        target.setStatus(PriceStatus.PUBLISHED);
        updateById(target);
        // 事务内仅发应用事件（A.4.2-7 禁事务内发 MQ）：AFTER_COMMIT 由 BillingEventPublisher 出 fy.topic，
        //   事务回滚则广播不出（调价未生效不误刷工作站缓存）；广播在发布时点发出（不等到点），
        //   载荷 effectiveFrom 供工作站定时刷新，到点后的自动切换归取价侧区间判定（见方法 javadoc）
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
