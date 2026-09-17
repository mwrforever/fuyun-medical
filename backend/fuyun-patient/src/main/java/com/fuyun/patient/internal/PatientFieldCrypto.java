package com.fuyun.patient.internal;

import com.fuyun.patient.properties.PatientCryptoProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor;

/**
 * 患者域敏感字段加密构件（拍板 4：落本模块 internal/，首个使用方不下沉 common；A.4.2-10 + M02 红线 3）。
 *
 * <p>算法：AES-256-GCM（Spring Security Crypto BOM 托管，随机 IV 前置 + Base64 承载）+
 * HMAC-SHA256 hex 盲索引（等值检索列 value_hash/id_card_no_hash/mobile_hash 载体）。
 * 无状态单例（多实例部署前提，A.1-9）：加密器与 Mac 原型构造后只读复用。
 *
 * <p>归 internal/ 包：模块内持久化设施非对外契约，禁止外部引用（backend 宪法 B.1）；
 * Bean 注册点为 PatientCryptoConfig @Import。
 */
public class PatientFieldCrypto {

    /** HMAC 算法名（JDK 标准名） */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** GCM 加密器（随机 IV 生成器由 CipherAlgorithm 默认供给，密文格式 = IV 前置 + 密文 + 认证标签） */
    private final AesBytesEncryptor encryptor;

    /** HMAC 密钥（hex 解码后仅驻内存，toString 不透出） */
    private final SecretKeySpec macKey;

    /**
     * 全参构造器（装配归 PatientCryptoConfig）：密钥 hex 解码与算法装配一次性完成，坏密钥启动即失败。
     *
     * @param properties 加密密钥配置（hex 文本），非空；来源：env 注入
     * @throws IllegalArgumentException 密钥 hex 非法时触发（Properties 层 @Pattern 已先拦截，此处为纵深防御）
     */
    public PatientFieldCrypto(PatientCryptoProperties properties) {
        byte[] dataKey = hexDecode(properties.dataKey());
        this.encryptor = new AesBytesEncryptor(
                new SecretKeySpec(dataKey, "AES"),
                AesBytesEncryptor.CipherAlgorithm.GCM.defaultIvGenerator(),
                AesBytesEncryptor.CipherAlgorithm.GCM);
        this.macKey = new SecretKeySpec(hexDecode(properties.macKey()), HMAC_ALGORITHM);
    }

    /**
     * AES-256-GCM 加密（随机 IV，同明文两次加密密文不同）。
     *
     * @param plaintext 明文，可空（可空列语义 null 进 null 出）；来源：建档/更新录入的证件号、手机号、住址
     * @return Base64 密文；null 入参返回 null
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] cipher = encryptor.encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(cipher);
    }

    /**
     * AES-256-GCM 解密（GCM 认证标签校验，密文篡改抛异常拒绝）。
     *
     * @param cipherText Base64 密文，可空；来源：patient 表 *_cipher 列
     * @return 明文；null 入参返回 null
     * @throws RuntimeException 密文非法或被篡改时触发（GCM 标签校验失败）；建议处理策略：按数据损坏处置，
     *                          禁止向调用方返回部分明文
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        byte[] plain = encryptor.decrypt(Base64.getDecoder().decode(cipherText));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * HMAC-SHA256 盲索引（跨实例确定性一致，等值检索唯一依据；禁用普通 SHA 兜底——防彩虹表反查）。
     *
     * @param plaintext 明文，可空；来源：与 encrypt 同源
     * @return 64 位小写 hex 摘要（对应 CHAR(64) 列）；null 入参返回 null
     */
    public String hash(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(macKey);
            byte[] digest = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return hexEncode(digest);
        } catch (Exception e) {
            // JDK 标准 HmacSHA256 不应抛出：属环境级异常，包装为运行时中断业务（禁静默吞错）
            throw new IllegalStateException("HMAC 盲索引计算失败（环境异常）", e);
        }
    }

    /** hex 文本转字节数组（构造期一次性，性能不敏感） */
    private static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 字节数组转小写 hex（摘要列载体） */
    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
