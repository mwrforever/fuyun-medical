package com.fuyun.billing.dto;

import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 计价规则登记请求（POST /api/v1/billing/pricing-rules/upsert，FU-M13-02 规则配置面）：
 * ruleCode 业务唯一 upsert；item_scope JSON 合法性在 service 落库前守卫（BILL-1027）。
 *
 * @param ruleCode    规则编码（业务唯一，uk 兜底），非空 ≤64；来源：医保办规则配置
 * @param ruleName    规则名称，非空 ≤128；来源：医保办规则配置
 * @param triggerType 触发型（TriggerType 七值），非空；来源：医保办规则配置
 * @param itemScope   项目集合 JSON 文本（{"itemClasses":[...],"itemIds":[...]}），非空；来源：规则配置表单
 * @param status      规则状态（ACTIVE/INACTIVE），非空；来源：医保办启停操作
 * @param remark      备注，可空 ≤255；来源：医保办规则配置
 */
public record PricingRuleUpsertRequest(
        @NotBlank @Size(max = 64) String ruleCode,
        @NotBlank @Size(max = 128) String ruleName,
        @NotNull TriggerType triggerType,
        @NotBlank String itemScope,
        @NotNull ItemStatus status,
        @Size(max = 255) String remark) {}
