package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 患者冻结请求（POST /patients/{patientId}/freeze，拍板 2 新增端点）。
 *
 * @param reason 冻结原因（身份存疑/风控要求等），非空且入事件载荷（非敏感字段）
 */
public record FreezeRequest(@NotBlank @Size(max = 255) String reason) {}
