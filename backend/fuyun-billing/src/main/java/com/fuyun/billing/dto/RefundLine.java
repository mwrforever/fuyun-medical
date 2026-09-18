package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 退费明细行（refund_fee_link 负向台账的请求侧载体，V603 列形态同源；组件清单为 Task 18 IT 与
 * Task 19 前端唯一依据，禁改名改序）。行金额不由前端传入，服务端按原费用行单价快照×本行数量算得。
 *
 * @param feeId          原费用明细 id（billing.fee_record 行），非空；来源：工作站费用查询选行
 * @param refundQuantity 本次退费数量（>0，可小数，支持部分退；服务端守卫累计退额不超费用行金额），
 *                       非空；来源：操作者录入
 */
public record RefundLine(
        @NotNull Long feeId, @NotNull @Positive BigDecimal refundQuantity) {}
