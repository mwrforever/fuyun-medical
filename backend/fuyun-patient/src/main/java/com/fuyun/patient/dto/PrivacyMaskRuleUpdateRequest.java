package com.fuyun.patient.dto;

/**
 * 脱敏规则维护请求（PUT /privacy-mask-rules/{ruleCode}，部分更新语义）：非空字段覆盖库值，
 * null=不变更（规则管理面变更经引擎每请求加载即时生效）。
 *
 * @param maskPattern 保留策略词表（PrivacyConstants.PATTERN_*），可空=不变更
 * @param exemptRoles 豁免角色集合（逗号分隔角色编码，经 M01 RBAC），可空=不变更；空串=清空豁免
 * @param enabled     启用标记，可空=不变更；false 停用后引擎跳过该规则（字段原值透传）
 */
public record PrivacyMaskRuleUpdateRequest(String maskPattern, String exemptRoles, Boolean enabled) {}
