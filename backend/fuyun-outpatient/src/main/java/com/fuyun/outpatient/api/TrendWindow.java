package com.fuyun.outpatient.api;

import java.time.LocalDate;

/**
 * 趋势统计日期窗（跨模块统计契约入参，A.7-1 强相关参数对象化——起止日期成对象整体传参）：
 * 闭区间 [fromDate, toDate]，供门诊人次按日聚合查询圈定窗口。
 *
 * @param fromDate 窗口起始日（含，北京钟面自然日），非空；来源：调用方（M19 工作台聚合取近 14 日）
 * @param toDate   窗口结束日（含，须 ≥ fromDate），非空；来源：同上
 */
public record TrendWindow(LocalDate fromDate, LocalDate toDate) {

    /**
     * 紧凑构造器守卫：窗口方向校验（from ≤ to），防调用方倒置窗口静默空结果。
     *
     * @throws IllegalArgumentException fromDate/toDate 为空或 fromDate 晚于 toDate（窗口倒置）
     */
    public TrendWindow {
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("趋势窗口非法：fromDate/toDate 须非空且 fromDate ≤ toDate");
        }
    }
}
