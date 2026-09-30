package com.fuyun.iot.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.entity.IotLinkageRuleEntity;
import com.fuyun.iot.enums.LinkageActionType;
import com.fuyun.iot.enums.LinkageTriggerSource;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 联动规则视图对象（联动规则域出网载体）：规则全字段出网。实体禁直出（宪法 B.1 出网边界），
 * 查询/登记/更新响应统一经 {@link #from} 静态工厂转换；trigger_condition/action_config JSONB
 * 原文经 objectMapper 反序列化为键值对（畸形 JSON 降级 null 不阻断出网，降级 warn 留痕
 * 规则标识与异常摘要、库内原文可对账，CommandLogVO 同款口径）。
 *
 * @param id               规则行雪花 id，非空（落库后回填）
 * @param ruleName         规则名称，非空
 * @param triggerSource    触发来源：ALARM_TRIGGERED/TELEMETRY_ANOMALY/DEVICE_STATUS，非空
 * @param triggerCondition 触发条件键值对，可空（原文畸形降级 null）
 * @param actionType       动作类型：NOTIFY/M01_NOTIFY/CALL_TRANSFER/NURSING_TASK/WARD_BROADCAST，非空
 * @param actionConfig     动作配置键值对，可空（未配置或原文畸形为 null）
 * @param targetWardId     目标病区 ID，可空
 * @param enabled          是否启用，非空
 * @param createdAt        创建时刻，可空（落库前为空）
 * @param updatedAt        更新时刻，可空（数据库触发器维护）
 */
@Slf4j
public record LinkageRuleVO(
        Long id,
        String ruleName,
        LinkageTriggerSource triggerSource,
        Map<String, Object> triggerCondition,
        LinkageActionType actionType,
        Map<String, Object> actionConfig,
        Long targetWardId,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 实体 → 出网视图（唯一转换出口）：JSONB 原文反序列化为键值对。
     *
     * @param entity       规则实体，非空；来源：mapper 查询或落库组装
     * @param objectMapper JSON 转换器，非空；来源：Boot 容器实例
     * @return 规则视图，非空
     */
    public static LinkageRuleVO from(IotLinkageRuleEntity entity, ObjectMapper objectMapper) {
        return new LinkageRuleVO(
                entity.getId(),
                entity.getRuleName(),
                entity.getTriggerSource(),
                parseJsonb(entity.getTriggerCondition(), objectMapper, entity, "triggerCondition"),
                entity.getActionType(),
                parseJsonb(entity.getActionConfig(), objectMapper, entity, "actionConfig"),
                entity.getTargetWardId(),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /**
     * JSONB 原文反序列化为键值对（畸形 JSON 降级 null 不阻断出网——留痕原文已在库，降级时
     * warn 留痕规则标识与异常摘要供对账，CommandLogVO 同款口径）。
     *
     * @param json         JSONB 原文，可空
     * @param objectMapper JSON 转换器，非空
     * @param entity       规则实体（warn 留痕业务标识来源：ruleId/ruleName），非空
     * @param field        出网字段名（triggerCondition/actionConfig，留痕定位用），非空
     * @return 键值对，可空
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonb(
            String json, ObjectMapper objectMapper, IotLinkageRuleEntity entity, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            // 畸形原文不阻断出网（留痕原文已在库，此处降级 null 出网）；静默降级改 warn 留痕
            // （EX-30）：规则标识+字段名+异常摘要入日志，库内原文可对账，可空契约不变
            log.warn(
                    "联动规则 JSONB 反序列化失败（降级 null 出网，库内原文可对账）：ruleId={}，ruleName={}，field={}，原因={}:{}",
                    entity.getId(),
                    entity.getRuleName(),
                    field,
                    e.getClass().getName(),
                    e.getMessage());
            return null;
        }
    }
}
