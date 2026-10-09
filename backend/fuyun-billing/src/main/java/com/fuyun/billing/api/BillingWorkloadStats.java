package com.fuyun.billing.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 收费工作量统计只读快照（BillingStatsPort 返回载体，M13 → M19 统计接口位契约）：当日收入
 * 与待结算两格计数 + 待支付费用事件行集（工作台事件流轮询源），全部纯只读聚合零患者级明细
 * （M19 红线 1 分析不回写；M13 Spec 收入统计接口位口径——收入一律经本口取数，禁消费
 * billing.* 事件载荷归集）。
 *
 * @param date              统计日（billing_date 北京钟面自然日）回显，非空
 * @param todayIncomeFen    当日收入合计（分；有效行=PENDING/CONFIRMED/SETTLED 三态，作废与
 *                          退费终态行不计），非空（无费用行为 0）
 * @param pendingSettleCount 待结算费用笔数（PENDING/CONFIRMED 且未结算 settlement_id IS NULL），
 *                          非空（无行为 0）
 * @param pendingFeeEvents  待支付费用事件行集（charged_at 降序有界），非空（无行为空清单）
 */
public record BillingWorkloadStats(
        LocalDate date, long todayIncomeFen, long pendingSettleCount, List<PendingFeeEvent> pendingFeeEvents) {

    /**
     * 待支付费用事件行（工作台事件流 billing 轮询源单行，纯计数/快照面零患者标识）。
     *
     * @param feeNo     费用编号（事件流幂等判别键），非空
     * @param itemName  项目名称快照（事件标题素材），非空
     * @param amountFen 金额（分，字符串化经全局 Jackson），非空
     * @param chargedAt 计费时刻（事件时点排序锚），非空
     */
    public record PendingFeeEvent(String feeNo, String itemName, long amountFen, OffsetDateTime chargedAt) {}
}
