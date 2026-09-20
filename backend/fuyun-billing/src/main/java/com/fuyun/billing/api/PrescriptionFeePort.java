package com.fuyun.billing.api;

/**
 * 处方联动费用作废对接面（M06 开方域进程内调用，api 包唯一出口；患者域 CardAccountLedger
 * 跨模块消费先例同型）。语义与 REST {@code POST /api/v1/billing/fees/{id}/cancel}（PR-3 已有）逐字
 * 同源——仅 PENDING 可作废、置 CANCELLED 并释放 billing_key；M06 处方作废在自身事务内调用
 * （同事务原子：处方 CANCELLED 与费用作废一体成败），供 pill 回执计数。
 */
public interface PrescriptionFeePort {

    /**
     * 按来源单据号作废处方触发的 PENDING 费用行组。
     *
     * @param sourceRef 来源单据引用（=rx_no，TriggerType.PRESCRIPTION_EFFECTIVE 通道）；
     *                  来源：M06 处方作废入参
     * @param reason    作废原因（审计留痕），非空白
     * @return 实际作废费用行数（0=无在途 PENDING 费用，幂等合法）
     */
    int cancelPendingBySourceRef(String sourceRef, String reason);
}
