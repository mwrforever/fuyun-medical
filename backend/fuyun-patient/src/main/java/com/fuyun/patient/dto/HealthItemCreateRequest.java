package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 健康档案项新增请求（POST /patients/{patientId}/health-items，FU-M02-05）。
 *
 * @param itemType  项类型 ALLERGY/CHRONIC/SURGERY/VACCINATION，非空；来源：临床医生站/手工补录
 * @param itemCode  字典 code（过敏物/ICD/疫苗；可空=手工项）；来源：M01 字典
 * @param itemName  项目名称，非空（录入原文）；来源：临床录入
 * @param severity  严重程度 MILD/MODERATE/SEVERE（过敏项建议必填，可空）；来源：临床录入
 * @param onsetDate 发生日期（ISO 文本，可空）
 * @param source    来源 DOCTOR_STATION/MANUAL，非空
 * @param note      备注（≤255，可空）
 */
public record HealthItemCreateRequest(
        @NotBlank @Pattern(regexp = "ALLERGY|CHRONIC|SURGERY|VACCINATION", message = "项类型非法")
        String itemType,

        @Size(max = 64) String itemCode,
        @NotBlank @Size(max = 128) String itemName,

        @Pattern(regexp = "MILD|MODERATE|SEVERE", message = "严重程度非法")
        String severity,

        String onsetDate,

        @NotBlank @Pattern(regexp = "DOCTOR_STATION|MANUAL", message = "来源非法")
        String source,

        @Size(max = 255) String note) {}
