package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 命令下发请求（POST /api/v1/iot/commands 请求体）：必携二次确认凭证标识（GETDEL 一次性消费，
 * 缺失/过期/已用 IOT-1015）。
 *
 * @param challengeId 二次确认凭证标识（confirm-challenge 端点签发），非空；来源：凭证签发响应
 * @param deviceId    目标设备号，非空；须与凭证签发载荷一致
 * @param commandName 命令名称，非空；须与凭证签发载荷一致
 * @param params      命令参数键值对，可空；须与凭证签发载荷一致（防签发后偷换参数）
 */
public record IssueCommandRequest(
        @NotBlank(message = "challengeId 不能为空（二次确认凭证必携）") String challengeId,
        @NotBlank(message = "deviceId 不能为空") String deviceId,
        @NotBlank(message = "commandName 不能为空") String commandName,
        Map<String, Object> params) {}
