package com.fuyun.patient.properties;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 加密密钥 properties 校验单测：格式约束（64 位 hex）与 toString 打码（密钥不泄露）两类。
 */
class PatientCryptoPropertiesTest {

    private static final String KEY_64_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("合法 hex 密钥通过 JSR-303 校验")
    void validHexKeysPassValidation() {
        var violations = validator.validate(new PatientCryptoProperties(KEY_64_HEX, KEY_64_HEX));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("空串与短密钥、非 hex 字符一律校验失败（启动期 fail-fast 依据）")
    void invalidKeysFailValidation() {
        assertThat(validator.validate(new PatientCryptoProperties("", KEY_64_HEX)))
                .isNotEmpty();
        assertThat(validator.validate(new PatientCryptoProperties("abc", KEY_64_HEX)))
                .isNotEmpty();
        assertThat(validator.validate(new PatientCryptoProperties(KEY_64_HEX, "zz".repeat(32))))
                .isNotEmpty();
    }

    @Test
    @DisplayName("toString 打码：密钥明文与 hex 片段均不得出现在字符串中")
    void toStringMasksKeys() {
        String text = new PatientCryptoProperties(KEY_64_HEX, KEY_64_HEX).toString();
        assertThat(text).contains("<masked:64>").doesNotContain(KEY_64_HEX.substring(0, 8));
    }
}
