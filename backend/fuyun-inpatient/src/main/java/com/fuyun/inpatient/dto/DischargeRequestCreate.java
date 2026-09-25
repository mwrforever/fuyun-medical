package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * 出院申请入参（POST /visits/{visitId}/discharge-request）——「预出院/明日出院」模式载体：
 * 仅承载预出院时间与离院方式（病案首页代码），申请医生取操作者上下文（禁前端传人）、
 * 欠费额/预审结果一律服务端计算回显（GC18 请求面零金额输入）。
 *
 * @param expectDischargeAt 预出院时间（明日出院实践载体；可携即时出院=当前时刻），非空；
 *                          来源：医生站出院申请单
 * @param dischargeWay      离院方式（病案首页代码 "1"/"2"/"3"/"4"/"5"/"9"——DischargeWay 词表），
 *                          非空；来源：医生站出院申请单（词表外服务层拒 IP-1022）
 */
public record DischargeRequestCreate(
        @NotNull OffsetDateTime expectDischargeAt, @NotBlank String dischargeWay) {}
