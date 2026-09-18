package com.fuyun.billing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 退费申请请求（POST /refunds，FU-M13-03 退侧；组件清单为 Task 18 IT 与 Task 19 前端唯一依据，
 * 禁改名改序）。申请金额不由前端传入：服务端按逐行单价快照×退费数量计算并勾稽（红线 1 退侧同源）。
 *
 * @param settlementId 原结算单 id（退费资金原路退回与分级判定的锚点），非空；来源：工作站结算回显
 * @param lines        退费明细行清单，非空；{@code @Valid} 级联锁行内 @NotNull（SettleRequest.payments
 *                     同款形态）；每行对应一条 refund_fee_link 负向台账
 * @param reason       退费理由，非空白（必填留痕，审计抽查要素）；来源：操作者录入
 */
public record RefundApplyRequest(
        @NotNull Long settlementId,
        @NotEmpty @Valid List<RefundLine> lines,
        @NotBlank String reason) {}
