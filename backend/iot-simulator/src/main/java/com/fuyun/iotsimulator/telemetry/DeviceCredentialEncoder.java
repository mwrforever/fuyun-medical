package com.fuyun.iotsimulator.telemetry;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * IoTDA「一机一密」MQTT 连接三元组编码器（BRIEF-PR4-01 §5）。
 *
 * <p><b>算法核对结论（2026-09-10，华为云官方文档《密钥鉴权_MQTT(S)协议接入》
 * support.huaweicloud.com/devg-iothub/iot_02_0203.html，官方示例已本地复算回归）</b>：
 * <ul>
 *   <li>clientId = {@code {deviceId}_0_0_{timestamp}}——第 2 段固定 0（设备 ID 身份标识）、
 *       第 3 段固定 0（HMACSHA256 且<b>不校验</b>时间戳准确度，时间戳仍必须携带，官方生成工具
 *       默认形态）；</li>
 *   <li>username = deviceId；</li>
 *   <li>password = HmacSHA256 的十六进制小写输出，<b>key = 时间戳、message = deviceSecret</b>
 *       （方向经官方示例复算核实：secret=12345678、timestamp=2025041401 →
 *       c75150e6cb841417396819e4d2ee4358a416344a03a083e3a8567074ddec820a，逐字符一致）；</li>
 *   <li>timestamp = UTC 时间 {@code yyyyMMddHH}（10 位小时粒度）。<b>与简报预判口径
 *       （13 位毫秒时间戳）偏离，以官方文档为准</b>，偏离已申报 CHANGELOG（2026-09-10 B4.4 条目）。</li>
 * </ul>
 *
 * <p>若真实联调发现服务端行为出入，改动面仅本类 + 单测（简报 §5 预案）。签名输入取整小时
 * 时间戳意味着长连接跨小时后口令不变（签名类型 0 下平台不校验时效，重连无需换密钥）。
 * deviceSecret 仅参与本计算，禁入日志与异常消息。
 */
public final class DeviceCredentialEncoder {

    /** 一机一密 MQTT 连接三元组：clientId / username / password（password 为敏感值，禁入日志） */
    public record MqttCredential(String clientId, String username, String password) {}

    /** clientId 第三段：HMACSHA256 且不校验时间戳准确度（官方生成工具默认，长联调场景最稳） */
    private static final String SIGN_TYPE_NO_TIMESTAMP_CHECK = "0";

    /** clientId 第二段：设备 ID 身份标识类型（官方文档固定值） */
    private static final String IDENTITY_TYPE_DEVICE_ID = "0";

    /** 平台时间戳格式：UTC 年月日时（10 位小时粒度，官方文档口径） */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /** HMAC-SHA256 算法标准名（JDK 内置 Provider 承载） */
    private static final String HMAC_SHA256 = "HmacSHA256";

    private DeviceCredentialEncoder() {}

    /**
     * 编码一机一密连接三元组。
     *
     * @param deviceId 平台注册设备标识（非空），来源：IOTDA_DEVICE_ID 环境变量
     * @param deviceSecret 一机一密设备密钥（非空，敏感值禁入日志），来源：IOTDA_DEVICE_SECRET 环境变量
     * @param clock 时间源（非空）；生产传 Clock.systemUTC()，测试注固定时钟保证断言确定性
     * @return 连接三元组，非空；password 为敏感值，调用方禁打日志
     */
    public static MqttCredential encode(String deviceId, String deviceSecret, Clock clock) {
        String timestamp = TIMESTAMP_FORMAT.format(clock.instant().atZone(ZoneOffset.UTC));
        String clientId =
                deviceId + "_" + IDENTITY_TYPE_DEVICE_ID + "_" + SIGN_TYPE_NO_TIMESTAMP_CHECK + "_" + timestamp;
        // 官方口径：时间戳作 HMAC 密钥、设备密钥作消息内容（方向经官方示例复算核实）
        String password = hmacSha256Hex(
                timestamp.getBytes(StandardCharsets.UTF_8), deviceSecret.getBytes(StandardCharsets.UTF_8));
        return new MqttCredential(clientId, deviceId, password);
    }

    /**
     * HmacSHA256 十六进制小写摘要。
     *
     * @param key HMAC 密钥字节（官方口径 = 时间戳）
     * @param message HMAC 消息字节（官方口径 = 设备密钥）
     * @return 64 位小写十六进制摘要，非空
     * @throws IllegalStateException JVM 缺失 HmacSHA256 算法或密钥非法（JDK 环境异常，不可恢复）
     */
    private static String hmacSha256Hex(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            byte[] digest = mac.doFinal(message);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 摘要计算失败（JDK 环境异常）", e);
        }
    }
}
