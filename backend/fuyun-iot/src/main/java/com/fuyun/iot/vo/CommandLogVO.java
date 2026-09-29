package com.fuyun.iot.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.entity.IotCommandLogEntity;
import com.fuyun.iot.enums.CommandDeliverMode;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.CommandStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 命令日志视图对象（命令下发域出网载体，iot_command_log 行全字段）：下发要素 + 状态机 + 结果
 * 留痕。实体禁直出（宪法 B.1 出网边界），查询/下发响应统一经 {@link #from} 转换；
 * command_params JSONB 原文经 objectMapper 反序列化为键值对（畸形 JSON 降级 null 不阻断出网，
 * 降级留痕：catch 内 warn 记录命令标识与异常，库内原文可对账）。
 * 命名注记：CommandVO 已被命令白名单标注视图（Task 4）占用，本类按表名承载日志视图。
 *
 * @param id          命令行雪花 id，非空
 * @param commandNo   命令业务号，非空
 * @param deviceId    目标设备号，非空
 * @param commandName 命令名称，非空
 * @param params      命令参数键值对，可空（无参命令或原文畸形为 null）
 * @param safetyLevel 命令安全等级，非空
 * @param operator    操作人，非空
 * @param deliverMode 下发通道，非空
 * @param status      命令状态，非空
 * @param issuedAt    下发时刻，非空
 * @param resultAt    结果时刻，可空（未终态为 null）
 * @param errorMsg    失败原因，可空
 * @param traceId     全链路追踪号，可空
 * @param createdAt   落行时刻，非空
 */
@Slf4j
public record CommandLogVO(
        Long id,
        String commandNo,
        String deviceId,
        String commandName,
        Map<String, Object> params,
        CommandSafetyLevel safetyLevel,
        String operator,
        CommandDeliverMode deliverMode,
        CommandStatus status,
        OffsetDateTime issuedAt,
        OffsetDateTime resultAt,
        String errorMsg,
        String traceId,
        OffsetDateTime createdAt) {

    /**
     * 实体 → 出网视图（唯一转换出口）：参数原文反序列化为键值对。
     *
     * <p>降级契约：参数原文畸形（反序列化失败）时 params 降级 null 出网、不阻断整体转换
     * （出网字段 null，库内留痕原文可对账）；降级时 warn 留痕，控制流不变（不抛出）。
     *
     * @param entity       命令日志实体，非空
     * @param objectMapper JSON 转换器，非空；来源：Boot 容器实例
     * @return 命令日志视图，非空（params 字段可空：无参命令或原文畸形降级 null）
     */
    public static CommandLogVO from(IotCommandLogEntity entity, ObjectMapper objectMapper) {
        Map<String, Object> params = null;
        if (entity.getCommandParams() != null && !entity.getCommandParams().isBlank()) {
            try {
                params = objectMapper.readValue(entity.getCommandParams(), Map.class);
            } catch (Exception e) {
                // 畸形参数原文不阻断出网（留痕原文已在库，此处降级 null 出网，warn 留痕可对账）
                log.warn(
                        "命令参数原文反序列化失败（降级 null 出网，库内原文可对账）：commandNo={}，deviceId={}，原因={}:{}",
                        entity.getCommandNo(),
                        entity.getDeviceId(),
                        e.getClass().getName(),
                        e.getMessage());
            }
        }
        return new CommandLogVO(
                entity.getId(),
                entity.getCommandNo(),
                entity.getDeviceId(),
                entity.getCommandName(),
                params,
                entity.getSafetyLevel(),
                entity.getOperator(),
                entity.getDeliverMode(),
                entity.getStatus(),
                entity.getIssuedAt(),
                entity.getResultAt(),
                entity.getErrorMsg(),
                entity.getTraceId(),
                entity.getCreatedAt());
    }
}
