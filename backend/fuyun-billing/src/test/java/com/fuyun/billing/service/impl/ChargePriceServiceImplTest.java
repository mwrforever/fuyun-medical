package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.api.ChargeItemPricePublishedPayload;
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
import com.fuyun.billing.service.IInsuranceMappingService;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 价格版本化单测（方案 3.4）：计费快照按时间区间取当前生效价、无价拒 BILL-1008、调价发布闭旧广播 + 区间倒挂守卫。
 *
 * <p>时间源：构造期注入 {@link MutableClock}（业务时钟 = ChargePriceServiceImpl 的 clock 依赖），
 * 「到点前取旧版本 / 到点后取新版本」与 SQL 区间条件的 now 取值均以固定基准时刻确定性断言，不依赖真实时钟。
 */
@ExtendWith(MockitoExtension.class)
class ChargePriceServiceImplTest {

    /** 固定基准时刻（UTC）：全部区间/时钟断言相对本时刻构造，与 JVM 默认时区（Asia/Shanghai）无关 */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-18T06:00:00Z");

    @Mock
    private ChargeItemPriceMapper priceMapper;

    @Mock
    private IInsuranceMappingService mappingService;

    @Mock
    private IChargeItemService itemService;

    @Mock
    private ApplicationEventPublisher events;

    private MutableClock clock;

    private ChargePriceServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChargeItemPrice.class);
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock(FIXED_INSTANT);
        service = new ChargePriceServiceImpl(mappingService, itemService, events, clock);
        ReflectionTestUtils.setField(service, "baseMapper", priceMapper);
        ReflectionTestUtils.setField(service, "entityClass", ChargeItemPrice.class);
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表） */
    private LambdaQueryWrapper<ChargeItemPrice> rendered(Wrapper<ChargeItemPrice> captured) {
        LambdaQueryWrapper<ChargeItemPrice> wrapper = (LambdaQueryWrapper<ChargeItemPrice>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    /** UTC 偏移时刻构造：测试造数统一入口，避免 JVM 默认时区参与区间断言 */
    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** 版本行夹具：区间端点显式给定（区间判定/时钟用例的确定性基础） */
    private ChargeItemPrice priceRow(
            long price, int version, PriceStatus status, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo) {
        ChargeItemPrice row = new ChargeItemPrice();
        row.setId((long) version);
        row.setChargeItemId(100L);
        row.setPrice(price);
        row.setVersion(version);
        row.setStatus(status);
        row.setEffectiveFrom(effectiveFrom);
        row.setEffectiveTo(effectiveTo);
        return row;
    }

    /** 已生效未闭行（历史用例沿用形态：起点在基准前 1 天、effective_to NULL） */
    private ChargeItemPrice openPublishedRow(long price, int version) {
        return priceRow(price, version, PriceStatus.PUBLISHED, at(FIXED_INSTANT.minus(Duration.ofDays(1))), null);
    }

    /**
     * 区间判定替身（内存版 SQL 谓词）：按 snapshot 查询谓词语义（status &lt;&gt; DRAFT、半开区间
     * [effective_from, effective_to) 覆盖 now、effective_from 倒序取首行）从版本链挑命中行。SQL 谓词本体
     * 对真库的落实由 BillingPriceSnapshotIT 验证；本替身用于确定性断言「服务把注入时钟的时刻传入查询，
     * 并按命中行冻结快照单价/版本」——缺时间条件即到点前误取新版本。
     */
    private void stubIntervalVersions(ChargeItemPrice... versions) {
        when(priceMapper.selectOne(any())).thenAnswer(inv -> {
            OffsetDateTime now = boundNow(inv.getArgument(0));
            return Arrays.stream(versions)
                    .filter(version -> version.getStatus() != PriceStatus.DRAFT)
                    .filter(version -> !version.getEffectiveFrom().isAfter(now))
                    .filter(version -> version.getEffectiveTo() == null
                            || version.getEffectiveTo().isAfter(now))
                    .max(Comparator.comparing(ChargeItemPrice::getEffectiveFrom)
                            .thenComparing(ChargeItemPrice::getVersion))
                    .orElse(null);
        });
    }

    /** 取查询 wrapper 绑定的 now 参数：区间判定基准必须来自注入时钟（production＝UTC 系统时钟） */
    private OffsetDateTime boundNow(Wrapper<ChargeItemPrice> wrapper) {
        List<OffsetDateTime> bounds = rendered(wrapper).getParamNameValuePairs().values().stream()
                .filter(OffsetDateTime.class::isInstance)
                .map(OffsetDateTime.class::cast)
                .toList();
        assertThat(bounds).as("区间判定查询必须绑定 now 参数（effective_from <= now）").isNotEmpty();
        assertThat(bounds).allSatisfy(bound -> assertThat(bound).isEqualTo(bounds.get(0)));
        return bounds.get(0);
    }

    @Test
    @DisplayName("快照取价：命中当前生效价 + ACTIVE 对照（贯标口径），冻结版本与目录字段")
    void snapshotFreezesPriceAndMapping() {
        stubIntervalVersions(openPublishedRow(3500L, 3));
        InsuranceMapping m = new InsuranceMapping();
        m.setNhsaCode("NHSA001");
        m.setCatalogVersion("2026Q3");
        m.setSelfPayRatio(new BigDecimal("0.10"));
        m.setLimitPrice(5000L);
        when(mappingService.effectiveMapping(100L)).thenReturn(m);

        PriceSnapshot snap = service.snapshot("C001", 100L);

        assertThat(snap.unitPrice()).isEqualTo(3500L);
        assertThat(snap.priceVersion()).isEqualTo(3);
        assertThat(snap.nhsaCode()).isEqualTo("NHSA001");
        assertThat(snap.limitPrice()).isEqualTo(5000L);
        // 生效查询 SQL 守卫钉死（Task 9 修复轮范式 + 本次时间区间判定修订）：必须限定本项目 +
        // status <> DRAFT（EXPIRED 行在未到 effective_to 闭点前仍生效，不得限定 status = PUBLISHED）
        // + 半开区间覆盖 now（effective_from <= now AND (effective_to IS NULL OR effective_to > now)）
        // + effective_from 倒序取首行——缺任一条件即提前生效 / 到点后漏取新版本 / 取错版本
        ArgumentCaptor<Wrapper<ChargeItemPrice>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(priceMapper).selectOne(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItemPrice> wrapper = rendered(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment())
                .contains("charge_item_id")
                .contains("status <>")
                .contains("effective_from <=")
                .contains("effective_to IS NULL OR effective_to >")
                .contains("effective_from DESC")
                .contains("LIMIT 1");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(100L, PriceStatus.DRAFT);
        // now 源＝注入时钟（UTC 瞬时）：到点前后同一查询语句仅比较基准不同，无 JVM/库时区参与
        assertThat(boundNow(wrapper)).isEqualTo(at(FIXED_INSTANT));
    }

    @Test
    @DisplayName("快照取价：未对照项目 nhsaCode 为 null（仅自费，不阻断快照）")
    void snapshotWithoutMappingIsSelfExpenseOnly() {
        stubIntervalVersions(openPublishedRow(2000L, 1));
        when(mappingService.effectiveMapping(100L)).thenReturn(null);

        PriceSnapshot snap = service.snapshot("C001", 100L);

        assertThat(snap.nhsaCode()).isNull();
        assertThat(snap.catalogVersion()).isNull();
    }

    @Test
    @DisplayName("快照取价：各版本区间均未覆盖当前时刻拒 BILL-1008")
    void snapshotWithoutPublishedPriceFails() {
        stubIntervalVersions(); // 项目无任何版本行（版本链为空）

        assertThatThrownBy(() -> service.snapshot("C001", 100L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE));
    }

    @Test
    @DisplayName("区间判定：未来起点版本到点前取价仍为旧版本（旧行已 EXPIRED 但区间覆盖当前时刻）")
    void snapshotBeforeEffectiveFromKeepsOldVersion() {
        OffsetDateTime due = at(FIXED_INSTANT.plus(Duration.ofHours(2)));
        // 发布未来起点版本后的库形态：旧行被闭到未来闭点（区间仍覆盖当前时刻）+ EXPIRED，新行待到点
        stubIntervalVersions(
                priceRow(3000L, 1, PriceStatus.EXPIRED, at(FIXED_INSTANT.minus(Duration.ofDays(1))), due),
                priceRow(4000L, 2, PriceStatus.PUBLISHED, due, null));

        PriceSnapshot snap = service.snapshot("C001", 100L);

        assertThat(snap.unitPrice()).isEqualTo(3000L);
        assertThat(snap.priceVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("区间判定：到点瞬间起取价自动切新版本（半开区间左闭右开，无调度无激活动作）")
    void snapshotAtEffectiveFromSwitchesToNewVersion() {
        OffsetDateTime due = at(FIXED_INSTANT.plus(Duration.ofHours(2)));
        stubIntervalVersions(
                priceRow(3000L, 1, PriceStatus.EXPIRED, at(FIXED_INSTANT.minus(Duration.ofDays(1))), due),
                priceRow(4000L, 2, PriceStatus.PUBLISHED, due, null));

        clock.advanceTo(due.toInstant().minusNanos(1)); // 到点前 1ns：旧行区间仍覆盖（effective_to > now）
        PriceSnapshot before = service.snapshot("C001", 100L);
        assertThat(before.priceVersion()).isEqualTo(1);
        assertThat(before.unitPrice()).isEqualTo(3000L);

        clock.advanceTo(due.toInstant()); // 到点瞬间：旧行闭点（右开不命中），新行起点命中（左闭）
        PriceSnapshot atDue = service.snapshot("C001", 100L);
        assertThat(atDue.priceVersion()).isEqualTo(2);
        assertThat(atDue.unitPrice()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("调价发布：DRAFT→PUBLISHED、旧行区间闭到新起点（未来时刻未提前闭）、广播携生效时刻")
    void publishClosesOldVersionAndBroadcasts() {
        OffsetDateTime due = at(FIXED_INSTANT.plus(Duration.ofHours(1)));
        ChargeItemPrice oldVersion =
                priceRow(3500L, 3, PriceStatus.PUBLISHED, at(FIXED_INSTANT.minus(Duration.ofDays(1))), null);
        ChargeItemPrice draft = priceRow(4000L, 4, PriceStatus.DRAFT, due, null);
        when(priceMapper.selectById(9L)).thenReturn(draft);
        when(priceMapper.selectOne(any())).thenReturn(oldVersion); // 当前未闭版本
        when(priceMapper.updateById(any(ChargeItemPrice.class))).thenReturn(1);
        ChargeItem item = new ChargeItem();
        item.setId(100L);
        item.setItemCode("C001");
        when(itemService.getById(100L)).thenReturn(item);

        service.publish(9L);

        // 闭旧：effective_to 落到新起点（未来时刻——旧行区间到点前仍覆盖当前时刻，取价继续命中旧价）+ EXPIRED；
        // 新行置 PUBLISHED 待到点（无二次激活动作）
        assertThat(oldVersion.getEffectiveTo()).isEqualTo(due);
        assertThat(oldVersion.getStatus()).isEqualTo(PriceStatus.EXPIRED);
        assertThat(draft.getStatus()).isEqualTo(PriceStatus.PUBLISHED);
        verify(priceMapper, times(2)).updateById(any(ChargeItemPrice.class));
        // 事件经 ApplicationEventPublisher 发布（AFTER_COMMIT 出 MQ 归 publisher，红线 A.4.2-7）；
        // 广播在发布时点发出、载荷携生效时刻 effectiveFrom（工作站据此到点刷新缓存）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.charge-item-price.published");
        assertThat(published.payload()).isInstanceOfSatisfying(ChargeItemPricePublishedPayload.class, payload -> {
            assertThat(payload.priceVersion()).isEqualTo(4);
            assertThat(payload.price()).isEqualTo(4000L);
            assertThat(payload.effectiveFrom()).isEqualTo(due.toInstant());
        });
        // 闭旧查询 SQL 守卫钉死：必须限定本项目 + status=PUBLISHED + effective_to IS NULL——
        // 删任一过滤即跨项目闭旧或把历史 EXPIRED 版本再闭一次，禁被静默删除
        ArgumentCaptor<Wrapper<ChargeItemPrice>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(priceMapper).selectOne(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItemPrice> wrapper = rendered(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment())
                .contains("charge_item_id")
                .contains("status")
                .contains("IS NULL");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(100L, PriceStatus.PUBLISHED);
    }

    @Test
    @DisplayName("调价发布：新起点早于当前版本起点拒 BILL-1004（区间倒挂/溯及既往），零写零广播")
    void publishRejectsBackdatedRangeAsBill1004() {
        ChargeItemPrice draft =
                priceRow(4000L, 2, PriceStatus.DRAFT, at(FIXED_INSTANT.minus(Duration.ofDays(2))), null);
        when(priceMapper.selectById(9L)).thenReturn(draft);
        when(priceMapper.selectOne(any()))
                .thenReturn(
                        priceRow(3500L, 1, PriceStatus.PUBLISHED, at(FIXED_INSTANT.minus(Duration.ofDays(1))), null));

        assertThatThrownBy(() -> service.publish(9L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICE_RANGE_CONFLICT));
        // 守卫先于闭旧落库：倒挂拒绝不得触达任何数据库写与广播（Spec §3.4 调价不溯既往）
        verify(priceMapper, never()).updateById(any(ChargeItemPrice.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("调价发布：非 DRAFT 行拒 BILL-1028 不广播")
    void publishRejectsNonDraft() {
        when(priceMapper.selectById(9L)).thenReturn(openPublishedRow(4000L, 4)); // 已 PUBLISHED

        assertThatThrownBy(() -> service.publish(9L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICE_STATE_NOT_ALLOWED));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("调价发布：行不存在拒 BILL-1005 且零写零广播")
    void publishRejectsMissingRowAsBill1005() {
        when(priceMapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.publish(9L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICE_NOT_FOUND));
        // 守卫先于闭旧落库：缺行不得触达任何数据库写与广播
        verify(priceMapper, never()).updateById(any(ChargeItemPrice.class));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("调价草稿落库：版本号项目内自增、状态 DRAFT 不进生效查询、零广播")
    void saveDraftIncrementsVersionAndPersistsDraftUnpublished() {
        ChargeItem item = new ChargeItem();
        item.setId(100L);
        item.setItemCode("C001");
        when(itemService.requireActiveByCode("C001")).thenReturn(item);
        // 项目内当前最大版本=3（生效查询链 oneOpt 经 selectOne 承载，与 snapshot 用例同桩位）
        when(priceMapper.selectOne(any())).thenReturn(openPublishedRow(3500L, 3));
        // 模拟 MP ASSIGN_ID 回填：insert 时置实体 id（saveDraft 末尾 return draft.getId() 拆箱前置）
        when(priceMapper.insert(any(ChargeItemPrice.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChargeItemPrice.class).setId(9L);
            return 1;
        });

        long id = service.saveDraft(new PriceDraftRequest(
                "C001", 4000L, at(FIXED_INSTANT.plus(Duration.ofDays(1))), PriceSource.OFFICIAL_DOC, "物价批文〔2026〕7 号"));

        assertThat(id).isEqualTo(9L);
        ArgumentCaptor<ChargeItemPrice> captor = ArgumentCaptor.forClass(ChargeItemPrice.class);
        verify(priceMapper).insert(captor.capture());
        ChargeItemPrice draft = captor.getValue();
        assertThat(draft.getVersion()).isEqualTo(4); // 版本项目内自增
        assertThat(draft.getStatus()).isEqualTo(PriceStatus.DRAFT); // 草稿不进生效查询（查询以 status <> DRAFT 排除）
        assertThat(draft.getPrice()).isEqualTo(4000L);
        verify(events, never()).publishEvent(any(Object.class)); // 草稿零广播（生效须显式 publish）
        // 版本自增查询 SQL 守卫钉死：必须限定本项目且按 version 倒序 LIMIT 1——删排序/限行即取错基准版本
        ArgumentCaptor<Wrapper<ChargeItemPrice>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(priceMapper).selectOne(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItemPrice> versionWrapper = rendered(wrapperCaptor.getValue());
        assertThat(versionWrapper.getSqlSegment())
                .contains("charge_item_id")
                .contains("version DESC")
                .contains("LIMIT 1");
        assertThat(versionWrapper.getParamNameValuePairs().values()).contains(100L);
    }

    @Test
    @DisplayName("调价草稿落库：priceSource 缺省回落 OFFICIAL_DOC（物价批文为默认来源）")
    void saveDraftDefaultsPriceSourceWhenAbsent() {
        ChargeItem item = new ChargeItem();
        item.setId(100L);
        item.setItemCode("C001");
        when(itemService.requireActiveByCode("C001")).thenReturn(item);
        when(priceMapper.selectOne(any())).thenReturn(null); // 项目首版：无历史版本从 1 起
        when(priceMapper.insert(any(ChargeItemPrice.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChargeItemPrice.class).setId(10L);
            return 1;
        });

        service.saveDraft(new PriceDraftRequest("C001", 2000L, at(FIXED_INSTANT.plus(Duration.ofDays(1))), null, null));

        ArgumentCaptor<ChargeItemPrice> captor = ArgumentCaptor.forClass(ChargeItemPrice.class);
        verify(priceMapper).insert(captor.capture());
        assertThat(captor.getValue().getPriceSource()).isEqualTo(PriceSource.OFFICIAL_DOC);
        assertThat(captor.getValue().getVersion()).isEqualTo(1); // 无历史版本从 1 起增
    }

    @Test
    @DisplayName("版本链查询：按项目返回 version 倒序全状态清单（管理面追溯）")
    void listVersionsReturnsChainOrderedByVersionDesc() {
        ChargeItemPrice v2 = openPublishedRow(3800L, 2);
        v2.setStatus(PriceStatus.EXPIRED);
        ChargeItemPrice v3 = openPublishedRow(3500L, 3);
        when(priceMapper.selectList(any())).thenReturn(List.of(v3, v2));

        assertThat(service.listVersions(100L)).containsExactly(v3, v2);

        // 版本链查询 SQL 守卫钉死：必须限定本项目且按 version 倒序（防全表拉取误当版本历史）
        ArgumentCaptor<Wrapper<ChargeItemPrice>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(priceMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItemPrice> wrapper = rendered(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("charge_item_id").contains("version DESC");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(100L);
    }

    /** 可推进时钟（测试专用）：以 AtomicReference 承载当前时刻，供「到点前/到点后」用例确定性推进 */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        private void advanceTo(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
