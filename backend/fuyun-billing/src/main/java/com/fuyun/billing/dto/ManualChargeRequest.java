package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 手工计费请求（POST /fees/manual，FU-M13-02 手工通道；红线 3 强制记录操作者与理由）。
 *
 * @param patientId 患者主索引，非空
 * @param visitId   CF-3 就诊号，非空
 * @param itemCode  收费项目编码，非空
 * @param quantity  数量（>0），非空
 * @param reason    手工计费理由，非空白（操作者由 controller 从上下文注入，不由前端传）
 */
public record ManualChargeRequest(
        @NotNull Long patientId,
        @NotNull String visitId,
        @NotNull String itemCode,
        @NotNull BigDecimal quantity,
        @NotBlank String reason) {}
