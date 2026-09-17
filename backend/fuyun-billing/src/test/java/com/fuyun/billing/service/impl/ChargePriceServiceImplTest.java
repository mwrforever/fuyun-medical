package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import java.time.OffsetDateTime;
import java.util.List;
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

/** 价格版本化单测（方案 3.4）：计费快照取当前生效价、无价拒 BILL-1008、调价发布广播 + 旧版本闭区间。 */
@ExtendWith(MockitoExtension.class)
class ChargePriceServiceImplTest {

    @Mock
    private ChargeItemPriceMapper priceMapper;

    @Mock
    private IInsuranceMappingService mappingService;

    @Mock
    private IChargeItemService itemService;

    @Mock
    private ApplicationEventPublisher events;

    private ChargePriceServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChargeItemPrice.class);
    }

    @BeforeEach
    void setUp() {
        service = new ChargePriceServiceImpl(mappingService, itemService, events);
        ReflectionTestUtils.setField(service, "baseMapper", priceMapper);
        ReflectionTestUtils.setField(service, "entityClass", ChargeItemPrice.class);
    }

    /** 取捕获的查询 wrapper 并渲染 SQL 片段（MP 条件参数在 getSqlSegment 惰性求值时才写入参数表） */
    private LambdaQueryWrapper<ChargeItemPrice> rendered(Wrapper<ChargeItemPrice> captured) {
        LambdaQueryWrapper<ChargeItemPrice> wrapper = (LambdaQueryWrapper<ChargeItemPrice>) captured;
        wrapper.getSqlSegment();
        return wrapper;
    }

    private ChargeItemPrice publishedRow(long price, int version) {
        ChargeItemPrice p = new ChargeItemPrice();
        p.setId(1L);
        p.setChargeItemId(100L);
        p.setPrice(price);
        p.setVersion(version);
        p.setStatus(PriceStatus.PUBLISHED);
        p.setEffectiveFrom(OffsetDateTime.now().minusDays(1));
        p.setEffectiveTo(null);
        return p;
    }

    @Test
    @DisplayName("快照取价：命中当前生效价 + ACTIVE 对照（贯标口径），冻结版本与目录字段")
    void snapshotFreezesPriceAndMapping() {
        when(priceMapper.selectOne(any())).thenReturn(publishedRow(3500L, 3));
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
        // 生效查询 SQL 守卫钉死（Task 9 修复轮范式）：必须限定本项目 + status=PUBLISHED + effective_to
        // IS NULL——「当前唯一生效版本」过滤是计费快照不漂移的前提，禁被静默删除
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
    @DisplayName("快照取价：未对照项目 nhsaCode 为 null（仅自费，不阻断快照）")
    void snapshotWithoutMappingIsSelfExpenseOnly() {
        when(priceMapper.selectOne(any())).thenReturn(publishedRow(2000L, 1));
        when(mappingService.effectiveMapping(100L)).thenReturn(null);

        PriceSnapshot snap = service.snapshot("C001", 100L);

        assertThat(snap.nhsaCode()).isNull();
        assertThat(snap.catalogVersion()).isNull();
    }

    @Test
    @DisplayName("快照取价：无生效价格版本拒 BILL-1008")
    void snapshotWithoutPublishedPriceFails() {
        when(priceMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.snapshot("C001", 100L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_UNAVAILABLE));
    }

    @Test
    @DisplayName("调价发布：DRAFT→PUBLISHED、闭当前生效版本 effective_to、发广播")
    void publishClosesOldVersionAndBroadcasts() {
        ChargeItemPrice draft = publishedRow(4000L, 4);
        draft.setStatus(PriceStatus.DRAFT);
        draft.setEffectiveFrom(OffsetDateTime.now());
        when(priceMapper.selectById(9L)).thenReturn(draft);
        when(priceMapper.selectOne(any())).thenReturn(publishedRow(3500L, 3)); // 旧生效版本
        when(priceMapper.updateById(any(ChargeItemPrice.class))).thenReturn(1);
        ChargeItem item = new ChargeItem();
        item.setId(100L);
        item.setItemCode("C001");
        when(itemService.getById(100L)).thenReturn(item);

        service.publish(9L);

        // 旧版本被闭区间（effective_to 回填）+ 新版本置 PUBLISHED
        verify(priceMapper, org.mockito.Mockito.atLeastOnce()).updateById(any(ChargeItemPrice.class));
        // 事件经 ApplicationEventPublisher 发布（AFTER_COMMIT 出 MQ 归 publisher，红线 A.4.2-7）
        ArgumentCaptor<Object> evt = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(evt.capture());
        BillingDomainEvent published = (BillingDomainEvent) evt.getValue();
        assertThat(published.eventType()).isEqualTo("billing.charge-item-price.published");
        assertThat(published.payload()).isInstanceOf(ChargeItemPricePublishedPayload.class);
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
    @DisplayName("调价发布：非 DRAFT 行拒 BILL-1028 不广播")
    void publishRejectsNonDraft() {
        when(priceMapper.selectById(9L)).thenReturn(publishedRow(4000L, 4)); // 已 PUBLISHED

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
        when(priceMapper.selectOne(any())).thenReturn(publishedRow(3500L, 3));
        // 模拟 MP ASSIGN_ID 回填：insert 时置实体 id（saveDraft 末尾 return draft.getId() 拆箱前置）
        when(priceMapper.insert(any(ChargeItemPrice.class))).thenAnswer(inv -> {
            inv.getArgument(0, ChargeItemPrice.class).setId(9L);
            return 1;
        });

        long id = service.saveDraft(new PriceDraftRequest(
                "C001", 4000L, OffsetDateTime.now().plusDays(1), PriceSource.OFFICIAL_DOC, "物价批文〔2026〕7 号"));

        assertThat(id).isEqualTo(9L);
        ArgumentCaptor<ChargeItemPrice> captor = ArgumentCaptor.forClass(ChargeItemPrice.class);
        verify(priceMapper).insert(captor.capture());
        ChargeItemPrice draft = captor.getValue();
        assertThat(draft.getVersion()).isEqualTo(4); // 版本项目内自增
        assertThat(draft.getStatus()).isEqualTo(PriceStatus.DRAFT); // 草稿不进生效查询（查询只认 PUBLISHED）
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

        service.saveDraft(
                new PriceDraftRequest("C001", 2000L, OffsetDateTime.now().plusDays(1), null, null));

        ArgumentCaptor<ChargeItemPrice> captor = ArgumentCaptor.forClass(ChargeItemPrice.class);
        verify(priceMapper).insert(captor.capture());
        assertThat(captor.getValue().getPriceSource()).isEqualTo(PriceSource.OFFICIAL_DOC);
        assertThat(captor.getValue().getVersion()).isEqualTo(1); // 无历史版本从 1 起增
    }

    @Test
    @DisplayName("版本链查询：按项目返回 version 倒序全状态清单（管理面追溯）")
    void listVersionsReturnsChainOrderedByVersionDesc() {
        ChargeItemPrice v2 = publishedRow(3800L, 2);
        v2.setStatus(PriceStatus.EXPIRED);
        ChargeItemPrice v3 = publishedRow(3500L, 3);
        when(priceMapper.selectList(any())).thenReturn(List.of(v3, v2));

        assertThat(service.listVersions(100L)).containsExactly(v3, v2);

        // 版本链查询 SQL 守卫钉死：必须限定本项目且按 version 倒序（防全表拉取误当版本历史）
        ArgumentCaptor<Wrapper<ChargeItemPrice>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(priceMapper).selectList(wrapperCaptor.capture());
        LambdaQueryWrapper<ChargeItemPrice> wrapper = rendered(wrapperCaptor.getValue());
        assertThat(wrapper.getSqlSegment()).contains("charge_item_id").contains("version DESC");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(100L);
    }
}
