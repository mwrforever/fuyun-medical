package com.fuyun.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 错误码契约测试（A.3-4 全项目唯一：BILL- 前缀 + 4 位数字，段内不重号） */
class BillingErrorCodeTest {

    @Test
    @DisplayName("BILL 码值唯一且形态合规")
    void errorCodesAreUniqueAndWellFormed() {
        assertThat(Arrays.stream(BillingErrorCode.values()).map(BillingErrorCode::getCode))
                .allMatch(code -> code.matches("^BILL-1\\d{3}$"))
                .doesNotHaveDuplicates();
    }
}
