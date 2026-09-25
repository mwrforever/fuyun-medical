package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会诊意见提交入参（POST /consultations/{no}/opinion）——ACCEPTED→COMPLETED 完成迁移的
 * 意见载体：意见文本随 CAS 同语句归档（V908 opinion 列，供 M09 病历引用）。
 *
 * @param opinion 会诊意见文本，非空（≤1000 字符）；来源：受邀科医生会诊意见单
 */
public record ConsultationOpinionRequest(
        @NotBlank(message = "opinion 不能为空") @Size(max = 1000, message = "opinion 超长（≤1000）")
        String opinion) {}
