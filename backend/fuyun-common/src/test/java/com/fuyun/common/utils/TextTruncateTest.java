package com.fuyun.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 列宽截断工具单测：短文本与 null 原样返回、超长按最大字符数截断（列宽防线的公共实现）。
 */
class TextTruncateTest {

    @Test
    @DisplayName("短文本与 null 原样返回（可空列语义保留）")
    void keepsShortTextAndNullAsIs() {
        assertThat(TextTruncate.truncate("abc", 10)).isEqualTo("abc");
        assertThat(TextTruncate.truncate(null, 10)).isNull();
    }

    @Test
    @DisplayName("超长文本按最大字符数截断（保留头部，死信原因的信封不合规标注在前部）")
    void truncatesOverlongTextKeepingHead() {
        String overlong = "信封不合规：" + "x".repeat(2000);

        String truncated = TextTruncate.truncate(overlong, 1000);

        assertThat(truncated).hasSize(1000);
        assertThat(truncated).startsWith("信封不合规：");
    }
}
