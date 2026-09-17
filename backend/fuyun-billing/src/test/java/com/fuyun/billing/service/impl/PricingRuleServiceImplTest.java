package com.fuyun.billing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.PricingRuleUpsertRequest;
import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.mapper.PricingRuleMapper;
import com.fuyun.common.exception.BizException;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 计价规则实现单测（FU-M13-02 规则配置面）：按触发型取生效规则（可空）、item_scope JSON
 * 落库前守卫（BILL-1027）与 ruleCode 业务唯一 upsert（无则插/有则改）。
 */
@ExtendWith(MockitoExtension.class)
class PricingRuleServiceImplTest {

    @Mock
    private PricingRuleMapper pricingRuleMapper;

    private PricingRuleServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 单测容器外手动初始化实体表信息（lambda 列名解析依赖 TableInfo，模块内既有 ServiceImpl 单测同款）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PricingRule.class);
    }

    @BeforeEach
    void setUp() {
        // 真实 ObjectMapper：item_scope JSON 试解析语义即被测对象，禁 mock 吞解析分支
        service = new PricingRuleServiceImpl(new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseMapper", pricingRuleMapper);
        ReflectionTestUtils.setField(service, "entityClass", PricingRule.class);
    }

    /** 规则登记请求样例（按类别圈选）；来源：医保办规则配置表单 */
    private PricingRuleUpsertRequest req(String itemScope) {
        return new PricingRuleUpsertRequest(
                "R-001", "开单确认计价", TriggerType.ORDER_CONFIRMED, itemScope, ItemStatus.ACTIVE, null);
    }

    @Test
    @DisplayName("按触发型取生效规则：命中 ACTIVE 返行、无命中返 null（引擎按无规则分支处理）")
    void activeByTriggerHitsActiveOrReturnsNull() {
        PricingRule rule = new PricingRule();
        rule.setId(3L);
        rule.setTriggerType(TriggerType.ORDER_CONFIRMED);
        rule.setStatus(ItemStatus.ACTIVE);
        // 同一 stub 链变参依次命中：第一次返生效规则，第二次返 null（未配置）
        when(pricingRuleMapper.selectOne(any())).thenReturn(rule, (PricingRule) null);

        assertThat(service.activeByTrigger(TriggerType.ORDER_CONFIRMED)).isSameAs(rule);
        assertThat(service.activeByTrigger(TriggerType.ORDER_CONFIRMED)).isNull();
    }

    @Test
    @DisplayName("规则登记：item_scope 非法 JSON 落库前拒 BILL-1027（配置错误显式暴露，禁静默）")
    void upsertRejectsInvalidItemScopeJsonAsBill1027() {
        assertThatThrownBy(() -> service.upsert(req("{\"itemClasses\":[")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.PRICING_RULE_SCOPE_INVALID));
        // 守卫先于查重落库：非法 JSON 不得触达任何数据库操作
        verify(pricingRuleMapper, never()).selectOne(any());
        verify(pricingRuleMapper, never()).insert(any(PricingRule.class));
    }

    @Test
    @DisplayName("规则登记 upsert：ruleCode 无命中插新行回填 id，有命中改原行（两分支一用例）")
    void upsertInsertsWhenCodeAbsentAndRewritesWhenPresent() {
        PricingRule existing = new PricingRule();
        existing.setId(5L);
        existing.setRuleCode("R-001");
        // 同一 stub 链变参依次命中：第一次 null→插新行，第二次命中→改原行
        when(pricingRuleMapper.selectOne(any())).thenReturn(null, existing);
        // 模拟 MP ASSIGN_ID 插入期回填主键（生产参数处理器行为一致）
        when(pricingRuleMapper.insert(any(PricingRule.class))).thenAnswer(inv -> {
            inv.getArgument(0, PricingRule.class).setId(6L);
            return 1;
        });

        // 无则插分支：触发型/状态按请求落列，返回回填雪花 id
        assertThat(service.upsert(req("{\"itemClasses\":[\"WEST_DRUG\"]}"))).isEqualTo(6L);
        ArgumentCaptor<PricingRule> inserted = ArgumentCaptor.forClass(PricingRule.class);
        verify(pricingRuleMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getTriggerType()).isEqualTo(TriggerType.ORDER_CONFIRMED);
        assertThat(inserted.getValue().getStatus()).isEqualTo(ItemStatus.ACTIVE);

        // 有则改分支：原行 id 保留，item_scope 以最新配置覆盖
        assertThat(service.upsert(req("{\"itemIds\":[9]}"))).isEqualTo(5L);
        ArgumentCaptor<PricingRule> updated = ArgumentCaptor.forClass(PricingRule.class);
        verify(pricingRuleMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(5L);
        assertThat(updated.getValue().getItemScope()).isEqualTo("{\"itemIds\":[9]}");
    }

    @Test
    @DisplayName("规则清单：全量返回（配置面列表页）")
    void listAllReturnsAllRules() {
        PricingRule first = new PricingRule();
        first.setRuleCode("R-001");
        PricingRule second = new PricingRule();
        second.setRuleCode("R-002");
        when(pricingRuleMapper.selectList(any())).thenReturn(List.of(first, second));

        assertThat(service.listAll()).containsExactly(first, second);
    }
}
