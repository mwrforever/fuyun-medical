package com.fuyun.billing.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.vo.PricingRuleVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计价规则域转换器单测（BUG-23 迁入守护；消解 BE-C1-09）：实体→出参与原 PricingRuleVO.from
 * 手写逐字段等价——全字段断言锁同名对位语义，并守护可空字段的 null 传播（原手写直传 null 语义）；
 * itemScope JSON 文本原样直出（配置面仅展示，解析归计价引擎，转换器不得加工）。
 */
class PricingRuleConverterTest {

    @Test
    @DisplayName("计价规则直映：7 字段全量等价（含 itemScope JSON 文本原样承载）")
    void ruleMapsAllFields() {
        PricingRule entity = new PricingRule();
        entity.setId(7301L);
        entity.setRuleCode("RULE-BED-DURATION");
        entity.setRuleName("床位费按日在院计费");
        entity.setTriggerType(TriggerType.DURATION);
        entity.setItemScope("{\"itemClasses\":[\"BED\"],\"itemIds\":[]}");
        entity.setStatus(ItemStatus.ACTIVE);
        entity.setRemark("日切任务 02:30 触发，出院日不计费");

        PricingRuleVO vo = PricingRuleConverter.INSTANCE.toVO(entity);

        assertThat(vo.getId()).isEqualTo(7301L);
        assertThat(vo.getRuleCode()).isEqualTo("RULE-BED-DURATION");
        assertThat(vo.getRuleName()).isEqualTo("床位费按日在院计费");
        assertThat(vo.getTriggerType()).isEqualTo(TriggerType.DURATION);
        assertThat(vo.getItemScope()).isEqualTo("{\"itemClasses\":[\"BED\"],\"itemIds\":[]}");
        assertThat(vo.getStatus()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(vo.getRemark()).isEqualTo("日切任务 02:30 触发，出院日不计费");
    }

    @Test
    @DisplayName("计价规则 null 传播：未填备注时直传 null 不转默认值")
    void rulePropagatesNullables() {
        PricingRule entity = new PricingRule();
        entity.setId(7302L);
        entity.setRuleCode("RULE-OP-ORDER");
        entity.setRuleName("门诊开单确认计费");
        entity.setTriggerType(TriggerType.ORDER_CONFIRMED);
        entity.setItemScope("{\"itemClasses\":[\"WEST_DRUG\",\"TRAD_DRUG\"],\"itemIds\":[]}");
        entity.setStatus(ItemStatus.ACTIVE);

        PricingRuleVO vo = PricingRuleConverter.INSTANCE.toVO(entity);

        // 原手写直传语义：remark null=未填备注——不得转默认值
        assertThat(vo.getRemark()).isNull();
        assertThat(vo.getRuleCode()).isEqualTo("RULE-OP-ORDER");
        assertThat(vo.getStatus()).isEqualTo(ItemStatus.ACTIVE);
    }
}
