package com.fuyun.billing.dto;

import com.fuyun.billing.enums.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 支付明细行（settlement.payment_details 列 JSON 形态 [{method,amount,channelRef}] 的请求侧同源
 * 载体，V603 列注释已冻结；组件清单为 Task 18 IT 与 Task 19 前端唯一依据，禁改名改序）。
 *
 * @param method     支付方式（CASH/BANK/SCAN/ONLINE/CARD_BALANCE/CHARGE_ON_CREDIT），非空
 * @param amount     金额（分，>0，服务端勾稽 Σ=结算总额；前端 string 承载，web A.3-6），非空——
 *                   硬校验恒正（0/负值 400）：负值行可使 Σ 勾稽假平但 CARD_BALANCE 合计不 >0
 *                   而跳过扣卡，payment_details 落库后驱动退费 execute 凭空全额入卡
 * @param channelRef 渠道引用，可空——CARD_BALANCE 行该字段为就诊卡账户 id 字符串（服务层
 *                   Long.parseLong 转 accountId，空/非法/多卡不一致 → BILL-1012 拒），其余行可空
 */
public record PaymentLine(
        @NotNull PaymentMethod method,
        @NotNull @Positive(message = "支付金额必须为正（分）") Long amount,
        String channelRef) {}
