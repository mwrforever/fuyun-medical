package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 告警模拟请求（POST /api/v1/iot/alarm-rules/{id}/simulate 请求体）：历史回放评估的时段窗口。
 * 仅 THRESHOLD 规则支持（透传/离线源无遥测行可回放）；时窗非法（起点不早于终点）IOT-1019 400。
 *
 * @param from 窗口起点（UTC，含），非空；来源：管理台时段选择
 * @param to   窗口终点（UTC，含），非空；来源：管理台时段选择
 */
public record SimulateAlarmRequest(
        @NotNull(message = "from 不能为空") Instant from,
        @NotNull(message = "to 不能为空") Instant to) {}
