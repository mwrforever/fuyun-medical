package com.fuyun.iot.registry;

/**
 * 设备凭证载体（IotDeviceRegistry.registerDevice 出参，record 不可变）——一机一密一次性透出面。
 *
 * <p>凭证红线（14-iot §9）：{@code secret} 只允许出现在本次 HTTP 响应体内，落库仅
 * {@code credentialRef}；禁止将本对象整体写入日志或任何持久化载体（record 默认 toString 会
 * 直出 secret，调用方禁打印本对象）。凭证展示面由前端一次性弹窗承载，服务端不二次下发。
 *
 * @param credentialRef 凭证引用（落库 iot_device.credential_ref，非密钥明文；格式为实现自定义
 *                      的稳定引用指针），非空
 * @param secret        一机一密凭证明文（UUID 形态，仅本次响应有效），非空；禁日志禁落库
 */
public record DeviceCredential(String credentialRef, String secret) {}
