package com.fuyun.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通用脱敏工具单元测试（B3.3 交付，BRIEF-PR3-01 §3.3）。
 *
 * <p>覆盖：手机号（11 位独立/嵌于长文本/非 11 位不误伤）、身份证（18 位含校验位 X/15 位老号/
 * 嵌于文本）、姓名（姓留名打星/单字/null/空白）、truncate（null/未超长/超长截断/非正长度）。
 * 脱敏统一走本工具是审计红线（BRIEF-PR3-01 §8-5），禁业务代码散落正则。
 */
class SensitiveMaskerTest {

    @Test
    @DisplayName("maskPhone：11 位手机号保留前 3 后 4，中间打星")
    void maskPhoneKeepsHeadThreeAndTailFour() {
        assertThat(SensitiveMasker.maskPhone("13812345678")).isEqualTo("138****5678");
    }

    @Test
    @DisplayName("maskPhone：嵌于文本中的手机号被脱敏，正文不受影响")
    void maskPhoneMasksWithinFreeText() {
        assertThat(SensitiveMasker.maskPhone("联系 13812345678 确认")).isEqualTo("联系 138****5678 确认");
    }

    @Test
    @DisplayName("maskPhone：非 11 位数字串（如工号、短号）不误伤")
    void maskPhoneLeavesNonPhoneDigitsUntouched() {
        assertThat(SensitiveMasker.maskPhone("1234567")).isEqualTo("1234567");
        assertThat(SensitiveMasker.maskPhone("EMP-0099")).isEqualTo("EMP-0099");
    }

    @Test
    @DisplayName("maskIdCard：18 位身份证保留前 6 后 4（含 X 校验位）")
    void maskIdCardKeepsHeadSixAndTailFourFor18Digits() {
        assertThat(SensitiveMasker.maskIdCard("11010119900101123X")).isEqualTo("110101********123X");
    }

    @Test
    @DisplayName("maskIdCard：15 位老号保留前 6 后 4")
    void maskIdCardKeepsHeadSixAndTailFourFor15Digits() {
        assertThat(SensitiveMasker.maskIdCard("110101900101123")).isEqualTo("110101*****1123");
    }

    @Test
    @DisplayName("maskName：姓名保留首字（姓），其余打星且长度不变")
    void maskNameKeepsSurnameAndMasksGivenName() {
        assertThat(SensitiveMasker.maskName("张三丰")).isEqualTo("张**");
        assertThat(SensitiveMasker.maskName("李四")).isEqualTo("李*");
    }

    @Test
    @DisplayName("maskName：单字、null、空白原样返回（无可脱敏部分）")
    void maskNameReturnsEdgeInputsAsIs() {
        assertThat(SensitiveMasker.maskName("张")).isEqualTo("张");
        assertThat(SensitiveMasker.maskName(null)).isNull();
        assertThat(SensitiveMasker.maskName("  ")).isEqualTo("  ");
    }

    @Test
    @DisplayName("truncate：超长截断到指定长度，未超长与 null 原样返回")
    void truncateCutsLongTextAndKeepsShortText() {
        assertThat(SensitiveMasker.truncate("abcdef", 4)).isEqualTo("abcd");
        assertThat(SensitiveMasker.truncate("abc", 4)).isEqualTo("abc");
        assertThat(SensitiveMasker.truncate(null, 4)).isNull();
    }

    @Test
    @DisplayName("truncate：非正长度返回空串（防御性，禁越界）")
    void truncateReturnsEmptyForNonPositiveLimit() {
        assertThat(SensitiveMasker.truncate("abc", 0)).isEmpty();
        assertThat(SensitiveMasker.truncate("abc", -1)).isEmpty();
    }
}
