package com.fuyun.iot.api;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 遥测时序查询只读端口（FU-M14-06，iot/api 对外契约）：ward 模块（Task 12 冷链温度曲线/输液
 * 看板）消费的跨模块查询面——ward 侧依赖注入本接口（ward 依赖 iot api 包 = Modulith verify
 * 把关的 api 面），实现归 fuyun-iot 模块内 TelemetryQueryServiceImpl（装配归 fuyun-app IotConfig）。
 *
 * <p>查询路由语义与 REST 面同源（三档：≤24h 且 raw 走明细 / 超 24h 或显式 granularity 走连续
 * 聚合 / 超 90 天强制 cagg_1h）；granularity 取值 raw|1min|1h，非法值按 400 语义拒绝
 * （IOT-1019）。端口只暴露单设备单指标最小消费面，病区/患者维度与分页面不经此端口。
 */
public interface IotTelemetryQueryPort {

    /**
     * 查询单设备单指标时序点列（按路由档位自动选择明细或连续聚合数据源）。
     *
     * @param deviceId    IoTDA 设备标识，非空；来源：消费方业务上下文
     * @param metricCode  指标编码（术语归一后），非空
     * @param from        起始时刻（含，UTC 语义），非空；须早于 to
     * @param to          结束时刻（不含），非空
     * @param granularity 档位 raw|1min|1h；null=自动（≤24h 走明细，超 24h 走 1 分钟聚合；
     *                    超 90 天无论入参恒强制 1 小时聚合），非法值拒绝
     * @return 时序点清单（time 升序稳定），非空；无数据返回空清单
     * @throws com.fuyun.common.exception.BizException IOT-1019（400；时窗/档位非法）
     */
    List<TelemetryPoint> series(
            String deviceId, String metricCode, OffsetDateTime from, OffsetDateTime to, String granularity);
}
