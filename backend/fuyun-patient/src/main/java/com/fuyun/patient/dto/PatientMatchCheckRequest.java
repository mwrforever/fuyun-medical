package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 建档前匹配预检请求（POST /api/v1/patient/patients/match-check，FU-M02-01「建档即触发实时重复检测」前置形态）。
 *
 * @param name    姓名，非空；来源：录入
 * @param sex     性别字典 code，非空；来源：录入
 * @param birthDate 出生日期（ISO 文本，可空）；来源：录入
 * @param idCardNo 身份证号原文（可空；强标识匹配依据，禁日志）；来源：录入
 * @param mobile  手机号原文（可空；弱标识评分依据，禁日志）；来源：录入
 */
public record PatientMatchCheckRequest(
        @NotBlank String name, @NotBlank String sex, String birthDate, String idCardNo, String mobile) {}
