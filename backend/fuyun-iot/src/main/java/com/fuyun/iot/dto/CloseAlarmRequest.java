package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 告警关闭请求（POST /api/v1/iot/alarms/{alarmNo}/close 请求体）：关闭原因强制（brief 冻结
 * 「reason 必填」），列宽防线 255。
 *
 * @param reason 关闭原因，非空；来源：操作者填写
 */
public record CloseAlarmRequest(
        @NotBlank(message = "reason 不能为空") @Size(max = 255, message = "reason 最长 255 字符")
        String reason) {}
