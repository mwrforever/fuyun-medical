package com.fuyun.iot.vo;

/**
 * 设备凭证换发结果（POST /api/v1/iot/devices/{deviceId}/credential-reset 出参，record 不可变）
 * ——热更新语义的一次性透出面：新 secret 仅随本次响应交付（前端一次性弹窗承载，服务端不二次
 * 下发），设备侧重置即生效自行重连。
 *
 * <p>凭证红线（14-iot §9）：{@code secret} 禁日志禁落库（本地仅 credentialRef 轮换入
 * iot_device.credential_ref）；禁止将本对象整体写入日志（record 默认 toString 会直出 secret）。
 *
 * @param deviceId      设备标识（IoTDA 自然键），非空
 * @param credentialRef 换发后凭证引用（本地 credential_ref 轮换后值，非密钥明文），非空
 * @param secret        换发后一机一密凭证明文（UUID 形态，仅本次响应有效），非空；禁日志禁落库
 */
public record DeviceCredentialResetVO(String deviceId, String credentialRef, String secret) {}
