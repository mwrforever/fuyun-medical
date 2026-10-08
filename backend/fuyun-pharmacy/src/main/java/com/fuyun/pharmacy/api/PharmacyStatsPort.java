package com.fuyun.pharmacy.api;

/**
 * 药事工作量统计只读端口（M06 → M19 统计接口位，api 包唯一出口；BillingAccountQueryPort
 * 同款先例）：批次 2 册 2 工作台聚合的药事取数面——待配药在途单计数与事件行集，全部纯只读
 * 计数零状态迁移零业务写（M19 红线 1 分析不回写）。
 */
public interface PharmacyStatsPort {

    /**
     * 药事工作量统计聚合（待配药计数 + 事件行集一次取全）。
     *
     * @return 工作量统计快照，非空；无在途单返回零值/空清单视图（不造数）
     */
    PharmacyWorkloadStats pendingStats();
}
