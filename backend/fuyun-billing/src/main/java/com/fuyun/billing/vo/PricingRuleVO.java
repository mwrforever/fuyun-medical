package com.fuyun.billing.vo;

import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import lombok.Getter;
import lombok.Setter;

/**
 * 计价规则出参（FU-M13-02 规则配置面）：规则清单/登记回显载体。
 * itemScope 保持 JSON 文本原样直出（配置面仅展示，解析归计价引擎 Task 11）。
 * 实体→出参直映归 {@link com.fuyun.billing.convert.PricingRuleConverter}（BUG-23 迁入，禁实体直出）。
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
}
