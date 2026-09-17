package com.fuyun.billing.vo;

import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import lombok.Getter;
import lombok.Setter;

/**
 * 计价规则出参（FU-M13-02 规则配置面）：规则清单/登记回显载体。
 * itemScope 保持 JSON 文本原样直出（配置面仅展示，解析归计价引擎 Task 11）。
 */
@Getter
@Setter
public class PricingRuleVO {

    /** 规则 id（雪花） */
    private Long id;

    /** 规则编码（业务唯一） */
    private String ruleCode;

    /** 规则名称 */
    private String ruleName;

    /** 触发型（TriggerType 七值） */
    private TriggerType triggerType;

    /** 项目集合 JSON 文本（{"itemClasses":[...],"itemIds":[...]}） */
    private String itemScope;

    /** 规则状态 ACTIVE/INACTIVE */
    private ItemStatus status;

    /** 备注（可空） */
    private String remark;

    /**
     * 实体 → 出参静态工厂（controller 出网边界专用，禁实体直出）。
     *
     * @param rule 规则实体，非空；来源：service 事务内查询结果
     * @return 出参 VO，非空
     */
    public static PricingRuleVO from(PricingRule rule) {
        PricingRuleVO vo = new PricingRuleVO();
        vo.setId(rule.getId());
        vo.setRuleCode(rule.getRuleCode());
        vo.setRuleName(rule.getRuleName());
        vo.setTriggerType(rule.getTriggerType());
        vo.setItemScope(rule.getItemScope());
        vo.setStatus(rule.getStatus());
        vo.setRemark(rule.getRemark());
        return vo;
    }
}
