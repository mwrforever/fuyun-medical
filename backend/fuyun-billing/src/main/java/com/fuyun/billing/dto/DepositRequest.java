package com.fuyun.billing.dto;

import com.fuyun.billing.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 住院预交金缴存请求（POST /deposits，FU-M13-04；组件清单为 Task 18 IT 与 Task 19 前端唯一依据，
 * 禁改名改序）。
 *
 * @param patientId           患者主索引，非空
 * @param visitId             CF-3 住院就诊号（I 前缀 14 位定长，服务层守卫），非空
 * @param amountFen           缴存金额（分，>0），非空
 * @param paymentMethod       支付方式，非空——服务层守卫仅 CASH/BANK/SCAN/ONLINE（V604 列口径，
 *                            CARD_BALANCE/CHARGE_ON_CREDIT 属结算支付明细域，混入缴存拒 400）
 * @param channelRef          渠道流水号，可空（扫码/线上渠道对账锚点）
 * @param warningThresholdFen 欠费预警阈值（分），可空——仅开户时生效，缺省取
 *                            BillingProperties#depositWarningThresholdFen（Task 16 注入）
 */
public record DepositRequest(
        @NotNull Long patientId,
        @NotNull String visitId,
        @NotNull @Positive Long amountFen,
        @NotNull PaymentMethod paymentMethod,
        String channelRef,
        @Positive Long warningThresholdFen) {}
