package com.fuyun.ward.vo;

import java.util.List;

/**
 * 输液看板视图（GET /api/v1/ward/infusion-board/{wardId} 出网载体）：病区维度输液设备余量/滴速
 * 聚合与告警档位（iot 遥测最新值经 IotTelemetryQueryPort series 末点取值——Port 无单点 latest
 * 查询面，实测结论注记）。告警行实时面复用 iot WS 推送主题（后端零改动，前端 Task 16 消费）。
 *
 * @param wardId  病区 ID，非空
 * @param devices 设备行清单（余量/滴速/档位），非空；病区无输液设备为空清单
 */
public record InfusionBoardVO(Long wardId, List<InfusionBoardDeviceVO> devices) {}
