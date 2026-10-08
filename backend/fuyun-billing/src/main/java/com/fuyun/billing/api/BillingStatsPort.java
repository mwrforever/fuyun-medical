package com.fuyun.billing.api;

import java.time.LocalDate;

/**
 * 收费工作量统计只读端口（M13 → M19 统计接口位，api 包唯一出口；BillingAccountQueryPort
 * 同款先例）：批次 2 册 2 工作台聚合的收费取数面——当日收入合计、待结算笔数与待支付事件行集，
 * 全部纯只读计数零状态迁移零业务写（M19 红线 1 分析不回写）。
 */
public interface BillingStatsPort {

    /**
     * 收费工作量统计聚合（当日收入 + 待结算计数 + 待支付事件行集一次取全）。
     *
     * @param date 统计日（billing_date 口径，北京钟面自然日），非空；来源：M19 工作台聚合（当日）
     * @return 工作量统计快照，非空；无业务数据返回零值/空清单视图（不造数）
     */
    BillingWorkloadStats workloadStats(LocalDate date);
}
