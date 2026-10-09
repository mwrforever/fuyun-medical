package com.fuyun.outpatient.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 门诊工作量统计只读快照（OutpatientStatsPort.workloadStats 返回载体，M03 → M19 统计接口位
 * 契约）：当日人次/候诊两格计数 + 按科室候诊表 + 逐日趋势三段，全部纯计数聚合零患者级明细
 * （M19 红线 5 汇总天然脱敏；03 Spec 工作量统计口径，FU-M19-02 驾驶舱/册 2 工作台取数源）。
 *
 * @param window       聚合窗口（趋势段圈定范围回显），非空
 * @param todayVisits  当日门诊人次（北京钟面当日挂号 visit 行数，含急诊），非空（无数据为 0）
 * @param waitingCount 当前候诊人数（queue_ticket WAITING 在途行数），非空（无候诊为 0）
 * @param waitingByDept 按科室候诊表（候诊人数降序，仅观测科室入表；无候诊为空清单），非空
 * @param trend        逐日趋势行（窗口内逐日出点含零填充日，stat_date 升序），非空
 */
public record OutpatientWorkloadStats(
        TrendWindow window,
        long todayVisits,
        long waitingCount,
        List<DeptWaitingRow> waitingByDept,
        List<DailyVisitTrendRow> trend) {

    /**
     * 按科室候诊行（候诊表单行）：科室编码 + 在途候诊计数 + 最长等待时长。
     *
     * @param deptCode              开诊科室编码（queue_id 同源，M01 字典 code 引用），非空
     * @param waitingCount          该科室候诊人数（WAITING 行数），非空
     * @param longestWaitingMinutes 最长等待分钟（当前时刻 − 最早 queue_time，向下取整；时刻基准
     *                              北京钟面），非空
     */
    public record DeptWaitingRow(String deptCode, long waitingCount, long longestWaitingMinutes) {}

    /**
     * 逐日趋势行（14 日趋势单点）：当日人次 + 急诊人次双序列。
     *
     * <p>计数组件装箱 {@code Long}：本行经 VisitStatsMapper.xml 的显式 {@code <constructor>}
     * resultMap（dailyVisitTrendRowMap）构造器映射实例化——{@code <arg>} 的
     * {@code javaType="long"} 按 MyBatis 内建别名表解析为装箱 {@code java.lang.Long}（原始
     * {@code long} 的别名是 {@code _long}），构造器实参按该解析结果做类型匹配，原始
     * {@code long} 组件因装箱不匹配实例化失败，故两计数组件声明为装箱 {@code Long}；
     * COUNT(*) 聚合行恒非空，装箱不引入空值路径，窗口空集日的归零由 Port 实现零填充承担。
     *
     * @param statDate       统计日（registered_at 北京钟面自然日），非空
     * @param visitCount     当日门诊人次（全部类型），非空（零填充日为 0）
     * @param emergencyCount 当日急诊人次（visit_type=EMERGENCY），非空（零填充日为 0）
     */
    public record DailyVisitTrendRow(LocalDate statDate, Long visitCount, Long emergencyCount) {}
}
