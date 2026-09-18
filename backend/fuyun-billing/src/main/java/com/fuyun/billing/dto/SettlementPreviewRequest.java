package com.fuyun.billing.dto;

import com.fuyun.billing.enums.PayerType;
import jakarta.validation.constraints.NotNull;

/**
 * 预结算请求（POST /settlements/preview，FU-M13-03 划价收款依据；组件清单为 Task 18 IT 与
 * Task 19 前端唯一依据，禁改名改序）。
 *
 * @param patientId 患者主索引，非空；来源：工作站结算上下文
 * @param visitId   CF-3 就诊号，非空；来源：工作站结算上下文
 * @param payerType 支付类型（SELF_PAY/CITY_INS/…），非空；来源：收费员选定
 */
public record SettlementPreviewRequest(
        @NotNull Long patientId,
        @NotNull String visitId,
        @NotNull PayerType payerType) {}
