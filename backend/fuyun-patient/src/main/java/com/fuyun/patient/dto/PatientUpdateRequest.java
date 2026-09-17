package com.fuyun.patient.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 患者主数据更新请求（PUT /patients/{patientId}）；证件号不可经本端点变更（走标识补换留痕）。
 * 全字段可空 = 不更新该字段（部分更新语义）。
 *
 * @param name 姓名（≤64）
 * @param sex 性别字典 code
 * @param birthDate 出生日期（ISO 文本）
 * @param ethnicity 民族字典 code
 * @param maritalStatus 婚姻状况 code
 * @param occupation 职业
 * @param bloodType 血型 code
 * @param mobile 手机号原文（11 位校验；加密落库禁日志）
 * @param address 住址（≤255；加密落库禁日志）
 */
public record PatientUpdateRequest(
        @Size(max = 64) String name,
        String sex,
        String birthDate,
        String ethnicity,
        String maritalStatus,
        @Size(max = 32) String occupation,
        String bloodType,

        @Pattern(regexp = "(^$|^1\\d{10}$)", message = "手机号须为 11 位数字")
        String mobile,

        @Size(max = 255) String address) {}
