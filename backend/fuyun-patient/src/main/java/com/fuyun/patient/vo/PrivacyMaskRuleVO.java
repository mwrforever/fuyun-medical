package com.fuyun.patient.vo;

import java.util.List;

/**
 * 脱敏规则出参（GET/PUT /privacy-mask-rules）：exemptRoles 拆分后输出角色编码清单
 * （库内逗号分隔，出参结构化便于规则管理面展示与编辑）。
 *
 * @param ruleCode     规则编码（业务唯一，种子固定 MASK_* 五条）
 * @param targetField  目标字段词（PrivacyConstants.TARGET_*）
 * @param maskPattern  保留策略词表（PrivacyConstants.PATTERN_*）
 * @param exemptRoles  豁免角色编码清单（空清单=无人豁免）
 * @param enabled      启用标记（false 时引擎跳过该规则，字段原值透传）
 */
public record PrivacyMaskRuleVO(
        String ruleCode, String targetField, String maskPattern, List<String> exemptRoles, Boolean enabled) {}
