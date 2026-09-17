package com.fuyun.patient.api;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * visit_id 结构校验规则下发（CF-3 冻结载体之一，M02 Spec §3.4 结论 ⑥：结构校验规则由本模块发布供各模块自查）。
 *
 * <p>冻结结构：14 位定长字符串 {@code <类型码><yyyyMMdd><5 位当日流水>}——类型码 O=门诊/急诊（M03 签发）、
 * I=住院（M04 签发），其余模块不得签发（结论 ①）；签发后不可变、不可复用、不因合并/拆分改写（结论 ③），
 * 必须与签发时点 patient_id 同时落库（结论 ④）——本类只承载结构校验，不承担签发。
 *
 * <p>纯静态工具（零依赖、线程安全）：M03/M04 及任何消费方可直接引用；datePartOf 供对账场景提取签发日期。
 */
public final class VisitIdValidator {

    /** 类型码：门诊/急诊（签发主体唯一 = M03） */
    public static final String TYPE_OUTPATIENT = "O";

    /** 类型码：住院（签发主体唯一 = M04） */
    public static final String TYPE_INPATIENT = "I";

    /** visit_id 定长（1 位类型码 + 8 位日期 + 5 位流水） */
    public static final int VISIT_ID_LENGTH = 14;

    /** 结构正则：O|I + 8 位数字 + 5 位数字（日期语义合法性由 datePartOf 二次校验） */
    public static final Pattern VISIT_ID_PATTERN = Pattern.compile("^[OI]\\d{13}$");

    /** 签发日期段解析器（yyyyMMdd，线程安全不可变） */
    private static final DateTimeFormatter ISSUE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /** 纯静态工具类，禁止实例化（backend 宪法 A.2-6） */
    private VisitIdValidator() {}

    /**
     * 校验 visit_id 是否符合 CF-3 冻结结构（形态 + 日期段为真实日历日期）。
     *
     * @param visitId visit_id 原文，允许为空（空/null 一律 false，不抛异常）；来源：签发方落库值
     * @return true=结构合法；false=长度/类型码/数字段/日期任一不合法
     */
    public static boolean isValid(String visitId) {
        if (visitId == null || !VISIT_ID_PATTERN.matcher(visitId).matches()) {
            return false;
        }
        try {
            // 日期段二次校验：拦截 20261332 等形态合法但非真实日期的值（yyyyMMdd 严格解析）
            ISSUE_DATE_FORMAT.parse(datePartOf(visitId), LocalDate::from);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * 提取签发日期段（对账/展示场景用）。
     *
     * @param visitId visit_id 原文，非空且应先经 isValid 校验；来源：签发方落库值
     * @return 8 位 yyyyMMdd 日期段文本（如 20260916）；不做合法性校验（越界由调用方先行 isValid）
     */
    public static String datePartOf(String visitId) {
        return visitId.substring(1, 9);
    }
}
