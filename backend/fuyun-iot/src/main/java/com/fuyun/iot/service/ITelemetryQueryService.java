package com.fuyun.iot.service;

import com.fuyun.iot.api.IotTelemetryQueryPort;
import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.iot.dto.TelemetrySeriesRequest;
import com.fuyun.iot.vo.TelemetryLatestVO;
import java.util.List;

/**
 * 遥测时序查询服务（FU-M14-06，P2 PR-2 Task 10）：REST 查询面（三维度 + 三档路由 + 最新值快照
 * 兜底）与 ward 消费端口（{@link IotTelemetryQueryPort}）的模块内实现契约。
 *
 * <p>三档路由（14-iot Spec §3.2）：≤24h 且 raw 走明细 / 超 24h 或显式 granularity 走连续聚合 /
 * 超 90 天强制 cagg_1h；时窗/档位非法 IOT-1019。patient 维度强制数据范围校验（resolve 状态判断）。
 */
public interface ITelemetryQueryService {

    /**
     * 多维度时序查询（REST 面）：按 scope 展开设备范围后按路由档位选数据源。
     *
     * @param request 查询请求，非空（Bean Validation 校验后传入）
     * @return 时序点清单（time 升序），非空；无数据为空清单
     * @throws com.fuyun.common.exception.BizException IOT-1019（400；时窗/档位/维度标识非法或
     *                                                  患者数据范围校验不通过）
     */
    List<TelemetryPoint> series(TelemetrySeriesRequest request);

    /**
     * 最新值查询（Redis 快照兜底，P99 亚秒，供护理体征双通道与床旁屏）：读
     * {@code fy:iot:snapshot:latest:{deviceId}:{metricCode}} 快照（AlarmEngine 写入面）。
     *
     * @param deviceId   设备标识，非空
     * @param metricCode 指标编码，非空
     * @return 最新值视图；快照缺席（TTL 过期/从未上报）返回 value/occurredAt 为 null 的视图
     */
    TelemetryLatestVO latest(String deviceId, String metricCode);
}
