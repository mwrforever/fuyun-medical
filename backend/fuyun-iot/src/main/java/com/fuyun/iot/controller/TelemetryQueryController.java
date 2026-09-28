package com.fuyun.iot.controller;

import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.iot.dto.TelemetrySeriesRequest;
import com.fuyun.iot.service.ITelemetryQueryService;
import com.fuyun.iot.vo.TelemetryLatestVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 遥测时序查询端点（/api/v1/iot/telemetry 两端点，FU-M14-06 查询面）：时序曲线（三维度 +
 * 三档路由）与最新值快照兜底（P99 亚秒，供护理体征双通道与床旁屏）。
 *
 * <p>职责边界（宪法 B.1/A.1-8）：仅 @Valid 校验 + 调用查询服务 + 编排响应；路由判定与数据范围
 * 校验归服务层。装配归 fuyun-app IotConfig @Import（宪法 B.1）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/iot/telemetry")
public class TelemetryQueryController {

    /** 时序查询服务：两端点唯一业务出口 */
    private final ITelemetryQueryService telemetryQueryService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1；注入接口类型 B.2-2）。
     *
     * @param telemetryQueryService 时序查询服务，非空
     */
    public TelemetryQueryController(ITelemetryQueryService telemetryQueryService) {
        this.telemetryQueryService = telemetryQueryService;
    }

    /**
     * 遥测时序查询（GET /api/v1/iot/telemetry/series；三档路由：≤24h 且 raw 走明细 / 超 24h
     * 或显式档位走连续聚合 / 超 90 天强制 1 小时聚合）。
     *
     * @param request 查询请求（查询参数绑定，@Valid），非空
     * @return 时序点清单（time 升序）；200
     * @throws com.fuyun.common.exception.BizException IOT-1019（400；时窗/档位/维度标识非法或
     *                                                  患者数据范围校验不通过）
     */
    @GetMapping("/series")
    public List<TelemetryPoint> series(@Valid TelemetrySeriesRequest request) {
        return telemetryQueryService.series(request);
    }

    /**
     * 最新值查询（GET /api/v1/iot/telemetry/latest；Redis 快照兜底）。
     *
     * @param deviceId   设备标识（查询参数），非空
     * @param metricCode 指标编码（查询参数），非空
     * @return 最新值视图（快照缺席 value/occurredAt 为 null）；200
     */
    @GetMapping("/latest")
    public TelemetryLatestVO latest(
            @RequestParam @NotBlank(message = "deviceId 不能为空") String deviceId,
            @RequestParam @NotBlank(message = "metricCode 不能为空") String metricCode) {
        return telemetryQueryService.latest(deviceId, metricCode);
    }
}
