package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

/**
 * 遥测时序查询请求（GET /api/v1/iot/telemetry/series 查询参数绑定载体，FU-M14-06）。
 *
 * <p>三档路由入参（TelemetryQueryServiceImpl 判定）：scope 选定维度后对应标识必填（服务层校验，
 * 缺失 IOT-1019）；granularity 可空 = 自动档（≤24h 明细 / 超 24h 聚合）。时窗边界 from 含、
 * to 不含（半开区间，聚合桶按桶起点对齐）。
 *
 * @param scope       查询维度：device 按设备/patient 按患者/ward 按病区，非空；来源：工作台页面
 * @param deviceId    设备标识（scope=device 必填），可空
 * @param patientId   患者主索引（scope=patient 必填；服务层强制数据范围校验），可空
 * @param wardId      病区 ID（scope=ward 必填），可空
 * @param metricCode  指标编码（术语归一后），非空；来源：工作台曲线页面
 * @param from        起始时刻（含，UTC 语义），非空
 * @param to          结束时刻（不含），非空；服务层校验 from 早于 to（IOT-1019）
 * @param granularity 档位 raw|1min|1h，可空=自动档；非法值 IOT-1019
 */
public record TelemetrySeriesRequest(
        @NotBlank(message = "scope 不能为空")
        @Pattern(regexp = "device|patient|ward", message = "scope 只允许 device/patient/ward")
        String scope,

        String deviceId,
        Long patientId,
        Long wardId,
        @NotBlank(message = "metricCode 不能为空") String metricCode,
        @NotNull(message = "from 不能为空") OffsetDateTime from,
        @NotNull(message = "to 不能为空") OffsetDateTime to,

        @Pattern(regexp = "raw|1min|1h", message = "granularity 只允许 raw/1min/1h")
        String granularity) {}
