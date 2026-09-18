package com.fuyun.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * 医保预结算命令（SettlementServiceImpl preview 医保分支装配 → InsuranceGateway.preSettle 入参，
 * FU-M13-05；组件清单为 Task 15 冻结签名，禁改名改序）：lines=费用行快照列投影（FeeRecord 快照列
 * 同源透传），本地不自行计算基金拆分（红线 1，拆分一律以网关回执为准）。
 *
 * @param settlementDraftId preview 阶段 DRAFT 行 id（行未落库的预览前计算传 0，非空）
 * @param visitId           CF-3 定长就诊号（非空）；来源：preview 请求
 * @param lines             纳入结算费用行快照投影（非空；nhsaCode=null=目录外自费行）
 * @param idempotencyKey    幂等键（非空；preview 以结算编号承载，模拟通道据此派生确定性中心流水号）
 */
public record InsurancePreSettleCommand(
        @NotNull Long settlementDraftId,
        @NotNull String visitId,
        @NotEmpty List<Line> lines,
        @NotBlank String idempotencyKey) {

    /**
     * 费用行快照列投影（FeeRecord nhsa_code_snapshot/limit_price_snapshot/self_pay_ratio_snapshot/amount
     * 四列同源，计费时点快照为准）。
     *
     * @param nhsaCode     医保对照编码快照，可空（null=计费时点无 ACTIVE 对照，目录外自费行）
     * @param limitPrice   医保限价快照（分），可空（模拟通道单一目录不消费限价；真实通道 P5 回执校验用）
     * @param selfPayRatio 先自付比例快照（0-1），可空（缺省 0=甲类全额进目录拆分）
     * @param amount       行金额（分，非空）
     */
    public record Line(String nhsaCode, Long limitPrice, BigDecimal selfPayRatio, long amount) {}
}
