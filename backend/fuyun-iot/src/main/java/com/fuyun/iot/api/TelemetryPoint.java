package com.fuyun.iot.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 遥测时序点（FU-M14-06 时序查询出参契约，iot/api 对外契约）：单设备单指标单时刻的降采样聚合
 * 桶或明细点，ward 模块冷链温度曲线/输液看板（Task 12）与本模块 REST 查询面共用同一语言。
 *
 * <p>raw 明细路由下五聚合值恒等且均为该行采集值、sampleCount=1（明细点即未降采样桶）；
 * 连续聚合路由下取自 cagg_1min/cagg_1h 聚合桶（time_bucket 为桶起点，UTC）。
 * record 纯数据载体（宪法 A.1-2 透明浅不可变）。
 *
 * @param deviceId    IoTDA 设备标识，非空；来源：iot_telemetry/连续聚合 device_id
 * @param metricCode  指标编码，非空；来源：查询入参（术语归一后编码）
 * @param time        时点（raw=采集发生时刻；聚合=时间桶起点 UTC），非空
 * @param min         桶内最小值（raw=采集值），非空
 * @param max         桶内最大值（raw=采集值），非空
 * @param avg         桶内平均值（raw=采集值），非空
 * @param first       桶内首个采集值（按发生时刻最早，raw=采集值），非空
 * @param last        桶内末个采集值（按发生时刻最晚，raw=采集值），非空
 * @param sampleCount 桶内有效采样数（raw=1），非空
 */
public record TelemetryPoint(
        String deviceId,
        String metricCode,
        OffsetDateTime time,
        BigDecimal min,
        BigDecimal max,
        BigDecimal avg,
        BigDecimal first,
        BigDecimal last,
        Long sampleCount) {}
