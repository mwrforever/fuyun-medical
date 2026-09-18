package com.fuyun.billing.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MoneyUtil 分↔元换算单测（A.4.2-8 集中换算）：展示口径两位小数、金额恒正守卫、求和容空。 */
class MoneyUtilTest {

    @Test
    @DisplayName("分转元字符串：两位小数展示口径，正/零/负分均正确格式化")
    void fenToYuanStringFormatsTwoDecimals() {
        assertThat(MoneyUtil.fenToYuanString(1230L)).isEqualTo("12.30");
        assertThat(MoneyUtil.fenToYuanString(5L)).isEqualTo("0.05");
        assertThat(MoneyUtil.fenToYuanString(0L)).isEqualTo("0.00");
        // 负分（红冲负向）保留符号
        assertThat(MoneyUtil.fenToYuanString(-1230L)).isEqualTo("-12.30");
    }

    @Test
    @DisplayName("金额恒正守卫：零分与负分拒 BILL-1016，正分放行")
    void requirePositiveFenRejectsNonPositive() {
        assertThatThrownBy(() -> MoneyUtil.requirePositiveFen(0L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.AMOUNT_MISMATCH));
        assertThatThrownBy(() -> MoneyUtil.requirePositiveFen(-1L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(BillingErrorCode.AMOUNT_MISMATCH));
        MoneyUtil.requirePositiveFen(1L);
    }

    @Test
    @DisplayName("分金额求和：null/空清单合计 0，元素 null 跳过，正常累加")
    void sumFenHandlesNullAndSumsElements() {
        assertThat(MoneyUtil.sumFen(null)).isZero();
        assertThat(MoneyUtil.sumFen(List.of())).isZero();
        // Arrays.asList 允许 null 元素（对账聚合容脏），null 跳过不计
        assertThat(MoneyUtil.sumFen(Arrays.asList(100L, null, 230L))).isEqualTo(330L);
    }
}
