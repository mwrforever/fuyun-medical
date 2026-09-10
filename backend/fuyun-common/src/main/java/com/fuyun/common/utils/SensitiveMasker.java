package com.fuyun.common.utils;

import java.util.regex.Pattern;

/**
 * 通用敏感字段脱敏工具（纯字符串静态工具，零业务依赖——公共工具属 fuyun-common B.1 职责）。
 *
 * <p>业务定位：审计明细/失败原因/日志输出中敏感信息的统一脱敏出口（等保三级安全红线），
 * 禁止业务代码散落脱敏正则；P0 提供手机号/身份证/姓名三类通用掩码与截断能力，
 * 执业证书号等更多类型随 P1 敏感字段加密（A.4.2-10）一并补齐。
 *
 * <p>组合使用约定：同一文本多类敏感字段并存时先证后机（先 maskIdCard 再 maskPhone）——
 * 身份证号内含连续数字段，先执行手机号掩码会在证号中段误插星号；掩码含星号后互不干扰。
 * 线程安全：无状态静态工具（Pattern 预编译常量）。
 */
public final class SensitiveMasker {

    /** 18 位身份证号：前 6 位（行政区划）+ 8 位生日 + 3 位顺序码 + 1 位校验位（数字或 X/x）；
     * 前后视数字边界（(?<!\d)/(?!\d)）防长数字串（雪花 ID 等）内部误命中截断 */
    private static final Pattern ID_CARD_18 = Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?!\\d)");

    /** 15 位老号身份证号：前 6 位（行政区划）+ 9 位数字；同带数字边界（防长数字串内部误命中） */
    private static final Pattern ID_CARD_15 = Pattern.compile("(?<!\\d)(\\d{6})\\d{5}(\\d{4})(?!\\d)");

    /** 11 位手机号：前 3 位（号段）+ 4 位 + 后 4 位；同带数字边界（长数字串与工号短号均不误伤） */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\d{3})\\d{4}(\\d{4})(?!\\d)");

    /** 纯静态工具类，禁止实例化（backend 宪法 A.2-6） */
    private SensitiveMasker() {}

    /**
     * 手机号脱敏：11 位数字串保留前 3 后 4，中间 4 位打星；可嵌于任意文本（仅命中 11 位数字段）。
     *
     * @param text 待脱敏文本，可空；null 原样返回，非 11 位数字段不处理（防工号/短号误伤）
     * @return 脱敏后文本（如 138****5678）；无命中时与入参相同
     */
    public static String maskPhone(String text) {
        if (text == null) {
            return null;
        }
        return PHONE.matcher(text).replaceAll("$1****$2");
    }

    /**
     * 身份证号脱敏：18 位（含 X/x 校验位）与 15 位老号均保留前 6 后 4，中段打星；可嵌于任意文本。
     *
     * @param text 待脱敏文本，可空；null 原样返回
     * @return 脱敏后文本（如 110101********123X）；无命中时与入参相同
     */
    public static String maskIdCard(String text) {
        if (text == null) {
            return null;
        }
        // 18 位先于 15 位匹配：避免 18 位号被 15 位模式截取中段造成残留
        return ID_CARD_15
                .matcher(ID_CARD_18.matcher(text).replaceAll("$1********$2"))
                .replaceAll("$1*****$2");
    }

    /**
     * 姓名脱敏：保留首字（姓），其余逐字打星（长度不变）；单字姓名与空白文本无可脱敏部分原样返回。
     *
     * @param name 待脱敏姓名，可空；null 或空白原样返回
     * @return 脱敏后姓名（如 张**）；单字与 null/空白原样返回
     */
    public static String maskName(String name) {
        if (name == null || name.isBlank() || name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /**
     * 按最大长度截断文本：审计 fail_reason（500）/detail（1000）列宽防线的统一收口。
     *
     * @param text      原始文本，可空；null 原样返回
     * @param maxLength 最大保留长度（字符数），非正时返回空串（防御性，禁越界）
     * @return 截断后文本；长度不超限时与入参相同
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
