package com.fuyun.outpatient.api;

/**
 * 门诊工作量统计只读端口（M03 → M19 统计接口位，api 包唯一出口；BillingAccountQueryPort
 * 同款先例）：批次 2 册 2 工作台聚合与 M19 驾驶舱的门诊取数面——当日人次/候诊两格计数、
 * 按科室候诊表与逐日趋势（VisitStatsMapper.xml GROUP BY 聚合承载），全部纯只读计数，
 * 零状态迁移零业务写（M19 红线 1 分析不回写）。
 */
public interface OutpatientStatsPort {

    /**
     * 门诊工作量统计聚合（当日计数 + 候诊表 + 窗口趋势三段一次取全）。
     *
     * @param window 趋势统计窗口（闭区间，北京钟面自然日），非空；来源：M19 工作台聚合（近 14 日）
     * @return 工作量统计快照，非空；无业务数据返回零值/空清单视图（不造数）
     */
    OutpatientWorkloadStats workloadStats(TrendWindow window);
}
