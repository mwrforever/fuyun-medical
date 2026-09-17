package com.fuyun.patient.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 患者域字段加密密钥配置（A.4.2-10 密钥经环境变量注入；fuyun.patient.crypto 前缀）。
 *
 * <p>安全姿态：yml 仅留空占位（未设 env 即绑定空串 → @NotBlank 启动期校验失败 fail-fast，
 * 与 token-hmac-secret 同款）；toString 手写覆写打码（W-5 先例：record 自动 toString 会泄钥）。
 *
 * @param dataKey AES-256 数据密钥（64 位 hex = 32 字节）；来源：FUYUN_PATIENT_CRYPTO_DATA_KEY
 * @param macKey  HMAC-SHA256 盲索引密钥（≥64 位 hex）；来源：FUYUN_PATIENT_CRYPTO_MAC_KEY
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.patient.crypto")
public record PatientCryptoProperties(
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "dataKey 须为 64 位 hex（32 字节 AES-256）")
        String dataKey,

        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64,}$", message = "macKey 须为不少于 64 位 hex")
        String macKey) {

    /** toString 打码覆写：密钥明文禁入日志（等保红线；只透出长度指纹） */
    @Override
    public String toString() {
        return "PatientCryptoProperties[dataKey=<masked:" + dataKey.length() + ">, macKey=<masked:" + macKey.length()
                + ">]";
    }
}
