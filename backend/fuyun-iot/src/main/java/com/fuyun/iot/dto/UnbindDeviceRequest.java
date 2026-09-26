package com.fuyun.iot.dto;

import jakarta.validation.constraints.Size;

/**
 * 设备解绑请求（POST /api/v1/iot/bindings/{deviceId}/unbind 请求体）。
 * 原因强制语义归服务层（空白即 IOT-1010 拒绝——brief 指定码位，非 Bean Validation 400 形态，
 * 以业务异常码落 ProblemDetail 供前端定向提示）；本载体只做长度约束。
 *
 * @param reason 解绑原因（转床/消毒/维修/出院/调拨等，≤255 字符，列宽 V400 unbind_reason），非空白
 *               （服务层强制）；来源：操作员录入
 */
public record UnbindDeviceRequest(
        @Size(max = 255, message = "unbindReason 最长 255 字符") String reason) {}
