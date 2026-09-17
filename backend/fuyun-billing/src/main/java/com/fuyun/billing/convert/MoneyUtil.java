package com.fuyun.billing.convert;

import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.common.exception.BizException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 金额分↔元集中换算（backend 宪法 A.4.2-8，M13 资金域首落地；禁业务代码散落 *100/÷100）。
 * 全程 long 分整数运算，仅出参格式化经 BigDecimal 两位小数（HALF_UP）。
 */
public final class MoneyUtil {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

    private MoneyUtil() {}

    /**
     * 分转元字符串（展示口径，两位小数）。
     *
     * @param fen 金额（分），可负（红冲负向）
     * @return 元字符串（如 "12.30"），非空
     */
    public static String fenToYuanString(long fen) {
        return BigDecimal.valueOf(fen).divide(HUNDRED, 2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 金额恒正守卫（收入侧入参校验；红冲负向金额走专用路径不经本方法）。
     *
     * @param fen 待校验金额（分）
     * @throws BizException BILL-1016（400）金额非正时触发
     */
    public static void requirePositiveFen(long fen) {
        if (fen <= 0) {
            throw new BizException(BillingErrorCode.AMOUNT_MISMATCH, HttpStatus.BAD_REQUEST, "金额必须为正数（分）");
        }
    }

    /**
     * 分金额求和（防溢出由业务额度上限保障，long 加法）。
     *
     * @param fens 金额清单，可空（null/空 → 0）
     * @return 合计（分）
     */
    public static long sumFen(List<Long> fens) {
        if (fens == null || fens.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Long fen : fens) {
            if (fen != null) {
                total += fen;
            }
        }
        return total;
    }
}
