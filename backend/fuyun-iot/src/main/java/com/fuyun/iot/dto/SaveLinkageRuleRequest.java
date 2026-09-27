package com.fuyun.iot.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 联动规则保存请求（POST/PUT /api/v1/iot/linkage-rules 请求体，登记与更新共用一形）：
 * 触发条件词表校验（键域 alarm_type/metric_code/device_type、值为非空白文本、未知键/非对象/
 * 空白值拒保存 IOT-1018 409）由服务层承载——防永不命中规则入库。
 *
 * @param ruleName         规则名称，非空（≤128 字符）；来源：管理台表单
 * @param triggerSource    触发来源，非空；来源：管理台表单
 * @param triggerCondition 触发条件（JSON 对象，键值等值匹配），非空；来源：管理台表单
 * @param actionType       动作类型，非空；来源：管理台表单
 * @param actionConfig     动作配置（JSON 对象，动作参数透传），可空
 * @param targetWardId     目标病区 ID，可空（空=跟随触发源病区路由）；来源：管理台表单
 * @param enabled          是否启用，可空缺省 true（服务层补齐）
 */
public record SaveLinkageRuleRequest(
        @NotBlank(message = "ruleName 不能为空") @Size(max = 128, message = "ruleName 最长 128 字符")
        String ruleName,

        @NotNull(message = "triggerSource 不能为空") LinkageTriggerSource triggerSource,

        @NotNull(message = "triggerCondition 不能为空") JsonNode triggerCondition,

        @NotNull(message = "actionType 不能为空") LinkageActionType actionType,

        JsonNode actionConfig,

        Long targetWardId,

        Boolean enabled) {}
