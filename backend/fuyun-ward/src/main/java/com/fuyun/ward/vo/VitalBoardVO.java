package com.fuyun.ward.vo;

import java.util.List;

/**
 * 体征看板视图（GET /api/v1/ward/vital-board/{wardId} 出网载体，FU-M16-02 编排视图）：
 * 床垫在床/离床 presence 视图 + 采集质量注记。
 *
 * <p><b>编排面缺位申报</b>：设备→病区映射归 iot 绑定档案（ward 禁跨模块读表，Port 仅 series
 * 单设备单指标查询面——实测结论），本视图出 anomaly 注记（Redis 消费面，deviceId 维度全量，
 * 病区过滤待绑定面 PR-3 闭合）与 presence 指标锚说明；在床/离床逐设备状态与落卡权威归 M05
 * （PR-3，GC17⑥——本 PR 仅视图骨架）。
 *
 * @param wardId         病区 ID，非空
 * @param presenceMetric 床垫 presence 指标编码锚（词表缺位申报占位值），非空
 * @param anomalies      采集质量注记清单（断流异常快照），非空；无注记为空清单
 * @param note           编排缺位说明（前端注记展示），非空
 */
public record VitalBoardVO(Long wardId, String presenceMetric, List<VitalAnomalyVO> anomalies, String note) {}
