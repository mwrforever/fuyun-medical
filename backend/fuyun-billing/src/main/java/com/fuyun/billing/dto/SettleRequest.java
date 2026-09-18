package com.fuyun.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 正式结算请求（POST /settlements，FU-M13-03 收银确认；组件清单为 Task 18 IT 与 Task 19 前端
 * 唯一依据，禁改名改序）。幂等不设请求级键：settleNo 终态即流水号锚点，重放直返——医保通道
 * 请求级流水号语义归 Task 15 insurance_call_log，红线不建死配置字段。
 *
 * <p>cardAccountId 独立字段删除（2026-09-17 审查裁决①）：账户定位由 CARD_BALANCE 支付行承载，
 * 扣款金额由行内 amount 求和驱动，不再以 acct_pay_amount（医保个账，语义不同）误作扣卡额。
 *
 * @param settleNo 结算编号（preview 产出的结算单锚点），非空白；来源：预结算回显
 * @param payments 支付明细行清单，非空；{@code @Valid} 级联锁行内 @NotNull（QuoteRequest.lines
 *                 同款形态）；CARD_BALANCE 行 channelRef=就诊卡账户 id 字符串
 */
public record SettleRequest(
        @NotBlank String settleNo, @NotNull @Valid List<PaymentLine> payments) {}
