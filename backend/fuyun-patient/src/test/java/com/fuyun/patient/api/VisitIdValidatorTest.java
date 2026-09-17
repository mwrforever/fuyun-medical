package com.fuyun.patient.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * visit_id 结构校验单测（CF-3 冻结结构：O|I + yyyyMMdd + 5 位流水，14 位定长）：
 * 覆盖正常（O/I 两类型码）、边界（闰日与日期段非真实日期）、异常（null/短长/类型码/非数字）三类。
 */
class VisitIdValidatorTest {

    @Test
    @DisplayName("门诊类型码 O 的合法 visit_id 校验通过并可提取签发日期")
    void validOutpatientVisitIdPassesAndExtractsIssueDate() {
        assertThat(VisitIdValidator.isValid("O2026091600001")).isTrue();
        assertThat(VisitIdValidator.datePartOf("O2026091600001")).isEqualTo("20260916");
    }

    @Test
    @DisplayName("住院类型码 I 的合法 visit_id 校验通过")
    void validInpatientVisitIdPasses() {
        assertThat(VisitIdValidator.isValid("I2025123100042")).isTrue();
    }

    @Test
    @DisplayName("闰日 20240229 形态合法且为真实日期，校验通过")
    void leapDayVisitIdPasses() {
        assertThat(VisitIdValidator.isValid("O2024022900009")).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "O202609160000", // 边界：13 位（流水段缺 1 位）
                "O20260916000012", // 边界：15 位（流水段多 1 位）
                "X2026091600001", // 异常：类型码不在 O/I 词表
                "O2026133200001", // 异常：日期段非真实日期（13 月 32 日）
                "O2026023000001", // 异常：非闰年的 2 月 30 日
                "o2026091600001", // 异常：类型码小写
                "O20260916a0001" // 异常：流水段含非数字
            })
    @DisplayName("形态或日期段不合法的 visit_id 一律校验不通过且不抛异常")
    void malformedVisitIdFailsGracefully(String visitId) {
        assertThat(VisitIdValidator.isValid(visitId)).isFalse();
    }
}
