package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 二次确认凭证签发请求（POST /api/v1/iot/commands/confirm-challenge 请求体）：绑定目标设备与
 * 命令（签发时校验门槛并预占命令号），下发请求必须携同值 challengeId 且目标一致（防换设备）。
 *
 * @param deviceId    目标设备号（IoTDA 设备标识），非空；来源：管理台命令操作台
 * @param commandName 命令名称（物模型 commands[].name），非空
 * @param params      命令参数键值对，可空（无参命令为 null）；来源：操作台表单
 */
public record ConfirmChallengeRequest(
        @NotBlank(message = "deviceId 不能为空") String deviceId,
        @NotBlank(message = "commandName 不能为空") String commandName,
        Map<String, Object> params) {}
