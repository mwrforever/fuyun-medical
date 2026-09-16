package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.patient.properties.PatientCryptoProperties;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.keygen.BytesKeyGenerator;

/**
 * 字段加密构件单测（A.4.2-10 + M02 红线 3）：加密往返、盲索引确定性、密文不可预测（GCM 随机 IV）、
 * 篡改拒绝（GCM 认证标签）、null 安全五类；密钥用例内固定测试向量，禁真实密钥入库入测。
 */
class PatientFieldCryptoTest {

    /** 测试向量：32 字节 AES-256 数据密钥与 32 字节 HMAC 密钥（hex 64 字符，仅具测试意义） */
    private static final String DATA_KEY_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String MAC_KEY_HEX = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    /** 以测试密钥构造被测构件（与 PatientCryptoConfig 生产装配同构） */
    private PatientFieldCrypto crypto() {
        return new PatientFieldCrypto(new PatientCryptoProperties(DATA_KEY_HEX, MAC_KEY_HEX));
    }

    @Test
    @DisplayName("加密后可解密还原原文，且两次加密密文不同（GCM 随机 IV）")
    void encryptDecryptRoundTripWithDistinctCiphertexts() {
        PatientFieldCrypto crypto = crypto();
        String cipher1 = crypto.encrypt("110101199003077890");
        String cipher2 = crypto.encrypt("110101199003077890");
        assertThat(crypto.decrypt(cipher1)).isEqualTo("110101199003077890");
        assertThat(cipher1).isNotEqualTo(cipher2);
    }

    @Test
    @DisplayName("盲索引对同一明文跨实例确定性一致（等值检索前提），不同明文摘要不同")
    void hashIsDeterministicAcrossInstancesAndDistinctPerInput() {
        PatientFieldCrypto first = crypto();
        // 第二实例模拟多实例部署（各自构造，密钥同源）
        PatientFieldCrypto second = crypto();
        String hash = first.hash("110101199003077890");
        assertThat(hash).isEqualTo(second.hash("110101199003077890"));
        assertThat(hash).hasSize(64).isNotEqualTo(first.hash("110101199003077891"));
    }

    @Test
    @DisplayName("密文被篡改任一字符后解密拒绝（GCM 认证标签校验）")
    void decryptRejectsTamperedCiphertext() {
        PatientFieldCrypto crypto = crypto();
        String cipher = crypto.encrypt("13800001234");
        String tampered = ("A".equals(cipher.substring(0, 1)) ? "B" : "A") + cipher.substring(1);
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("encrypt/decrypt/hash 对 null 入参一律返回 null（可空列语义）")
    void nullSafeReturnsNullForAllOperations() {
        PatientFieldCrypto crypto = crypto();
        assertThat(crypto.encrypt(null)).isNull();
        assertThat(crypto.decrypt(null)).isNull();
        assertThat(crypto.hash(null)).isNull();
    }

    @Test
    @DisplayName("构件与 Spring Security Crypto GCM 产物互通（同密钥同 IV 生成器可解密）")
    void interopsWithSpringSecurityGcmEncryptor() {
        PatientFieldCrypto crypto = crypto();
        SecretKeySpec key = new SecretKeySpec(hexDecode(DATA_KEY_HEX), "AES");
        // 与构件同款 IV 生成器：交叉验证密文格式兼容（IV 前置 + GCM）
        BytesKeyGenerator ivGenerator = AesBytesEncryptor.CipherAlgorithm.GCM.defaultIvGenerator();
        AesBytesEncryptor raw = new AesBytesEncryptor(key, ivGenerator, AesBytesEncryptor.CipherAlgorithm.GCM);
        String cipherFromComponent = crypto.encrypt("interop-check");
        assertThat(new String(raw.decrypt(Base64.getDecoder().decode(cipherFromComponent))))
                .isEqualTo("interop-check");
    }

    /** hex 文本转字节数组（与构件内私有实现一致，测试侧独立实现以便交叉验证） */
    private static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
