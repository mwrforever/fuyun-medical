package com.fuyun.iot.record;

/**
 * 设备单日质量计数投影（FU-M14-11 日统计查询行，mapper XML 构造器映射载体）：当日接收总数与
 * 异常值数（quality != GOOD：SUSPECT+BAD）一次聚合下推数据库，禁全量捞行内存计数。
 *
 * <p>record 纯数据载体（宪法 A.1-2）；仅服务内日统计消费，非对外契约。
 *
 * @param totalCount   当日接收总数（iot_telemetry 该设备该日全部行数，含 BAD 标注行——标注不
 *                     丢弃口径下 received_count 承载实际接收量）
 * @param anomalyCount 当日异常值数（quality != GOOD 行数）
 */
public record DailyQualityCountRow(Long totalCount, Long anomalyCount) {}
