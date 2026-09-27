package com.fuyun.ward.vo;

import com.fuyun.iot.api.TelemetryPoint;
import java.util.List;

/**
 * 输液历史追溯视图（GET /api/v1/ward/infusion-history/{deviceId} 出网载体）：余量/滴速双曲线
 * （Port series 逐点）+ 告警聚合注记。
 *
 * <p><b>告警聚合缺位申报</b>：iot/api 无告警查询端口（实测仅 IotTelemetryQueryPort.series），
 * ward 禁跨模块读表（宪法 B.2-2），本视图只落遥测曲线面；告警列表聚合归 P1 经接口面补齐
 * （brief 预案分支）。
 *
 * @param deviceId     IoTDA 设备标识，非空
 * @param remainSeries 余量曲线（余量指标逐点，time 升序），非空；无数据为空清单
 * @param dropSeries   滴速曲线（滴速指标逐点，time 升序），非空；无数据为空清单
 * @param note         聚合注记（告警聚合缺位说明透出前端），非空
 */
public record InfusionHistoryVO(
        String deviceId, List<TelemetryPoint> remainSeries, List<TelemetryPoint> dropSeries, String note) {}
