package com.fuyun.ops.vo;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运营工作台总览出参（GET /api/v1/ops/workbench/overview，批次 2 册 2 前端消费契约）：
 * 指标带六格 + 14 日趋势 + 按科室候诊表三段聚合（M19 管理驾驶舱工作台形态首切片）。全部
 * 真实聚合零伪数据（无业务数据出零值/空清单），Long 字段经全局 Jackson Long→String 出网
 * （宪法 A.3-8 精度防线，前端以字符串接收）。
 *
 * <p>刷新契约：Redis 快照 read-through TTL 5s（{@code fy:ops:snapshot:workbench:overview}，
 * NurseBoardServiceImpl GC13 同款先例）——前端轮询频度下读路径不触库。
 *
 * @param metrics      指标带六格（四业务模块统计 Port 聚合，字段固定前端免判空），非空
 * @param trend        14 日趋势行（stat_date 升序含零填充日，稳定出点序列），非空
 * @param waitingTable 按科室候诊表（候诊人数降序，仅观测科室入表；无候诊为空清单），非空
 * @param generatedAt  快照生成时点（北京钟面），非空
 */
public record WorkbenchOverviewVO(
        Metrics metrics, List<TrendPoint> trend, List<WaitingRow> waitingTable, OffsetDateTime generatedAt) {

    /**
     * 指标带六格（字段固定，前端六卡直取；全部纯计数，零患者级明细）。
     *
     * @param todayVisits          今日门诊人次（outpatient.visit 当日挂号数，含急诊），非空
     * @param waitingCount         当前候诊人数（queue_ticket WAITING 在途行数），非空
     * @param todayIncomeFen       今日收入（分；billing.fee_record 当日有效行合计，字符串化出网），
     *                             非空
     * @param inHospitalCount      在院人数（nursing_ward_patient 在册投影行数），非空
     * @param pendingDispenseCount 待配药在途单数（pharmacy.dispense CREATED/PICKING 合计），非空
     * @param pendingSettleCount   待结算费用笔数（billing 当日 PENDING/CONFIRMED 未结算），非空
     */
    public record Metrics(
            long todayVisits,
            long waitingCount,
            long todayIncomeFen,
            long inHospitalCount,
            long pendingDispenseCount,
            long pendingSettleCount) {}

    /**
     * 14 日趋势单点（SVG 折线双序列数据源）。
     *
     * @param statDate       统计日（registered_at 北京钟面自然日），非空
     * @param visitCount     当日门诊人次（零填充日为 0），非空
     * @param emergencyCount 当日急诊人次（零填充日为 0），非空
     */
    public record TrendPoint(LocalDate statDate, long visitCount, long emergencyCount) {}

    /**
     * 按科室候诊表行（候诊排序锚=候诊人数降序）。
     *
     * @param deptCode              开诊科室编码（queue_id 同源，M01 字典 code 引用），非空
     * @param waitingCount          该科室候诊人数，非空
     * @param longestWaitingMinutes 最长等待分钟（当前时刻 − 最早 queue_time 向下取整），非空
     */
    public record WaitingRow(String deptCode, long waitingCount, long longestWaitingMinutes) {}
}
