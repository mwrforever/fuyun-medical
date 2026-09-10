package com.fuyun.iot.internal;

import com.fuyun.iot.properties.IotProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HTTP 兜底通道鉴权服务（B4.3，BRIEF-PR4-01 §1.5）：{@code POST /ingest/iotda-fallback} 的
 * 独立鉴权执行点——请求头 {@code X-Iot-Fallback-Token} 与 {@code FUYUN_IOT_FALLBACK_TOKEN}
 * 环境变量注入的共享密钥常量时间比对。
 *
 * <p>安全语义：① 常量时间比对（{@link MessageDigest#isEqual}，TokenServiceImpl 签名比对同款
 * 先例）防时序侧信道逐字节探测；② fail-closed——服务端未配置共享密钥（null 或空串占位，即 env
 * 未注入）时<b>一律拒绝</b>，不因"空对空相等"放行（兜底通道是 IoTDA 联动规则的写入口，未配置
 * 即未启用，放行等于无鉴权写通道）；③ 独立于 M01 令牌体系（禁走 TokenVerifier——14-iot §7
 * R5-09 口径：兜底通道为 IoTDA 机器凭据场景，P0 共享密钥头是等价物，专用凭证体系 P1）。
 *
 * <p>无状态单例：期望密钥字节构造期一次性派生（UTF-8），比对无共享可变状态。
 * 归 internal/ 包（模块内鉴权设施非对外契约，宪法 B.1）；Bean 注册点为 fuyun-app IotConfig
 * @Import；JaCoCo 按 BUNDLE 0.80 承载（internal 基础设施类，核心包 1.00 规则不误伤）。
 */
public class IotFallbackAuthService {

    /** 期望共享密钥字节（构造期从 IotProperties 派生；未配置为空数组 = fail-closed 载体） */
    private final byte[] expectedTokenBytes;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param properties IoT 配置属性，非空；仅消费 fallback().token()（env
     *                   FUYUN_IOT_FALLBACK_TOKEN 映射，禁 @Value 散落，红线 7）
     */
    public IotFallbackAuthService(IotProperties properties) {
        String token =
                properties.fallback() == null ? null : properties.fallback().token();
        this.expectedTokenBytes = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 兜底通道鉴权判定。
     *
     * <p>执行顺序：先判服务端未配置（fail-closed 短路，头值不进比对），再判头缺失，最后常量
     * 时间比对——三态失败统一 false，不区分原因（防按差异探测配置状态）。
     *
     * @param headerToken 请求头 X-Iot-Fallback-Token 原文，可空（头未携带为 null）；
     *                    来源：IotFallbackIngestController 从请求头提取
     * @return true=密钥一致（放行）；false=未配置/缺失/不匹配（一律拒绝，调用方统一 401 IOT-1001）
     */
    public boolean isAuthorized(String headerToken) {
        // fail-closed：服务端未配置共享密钥（null/空串占位）一律拒绝——头值不进比对
        if (expectedTokenBytes.length == 0) {
            return false;
        }
        if (headerToken == null || headerToken.isEmpty()) {
            return false;
        }
        // 常量时间比对（防时序攻击）：与 TokenServiceImpl 签名比对同款 JDK 先例
        return MessageDigest.isEqual(expectedTokenBytes, headerToken.getBytes(StandardCharsets.UTF_8));
    }
}
