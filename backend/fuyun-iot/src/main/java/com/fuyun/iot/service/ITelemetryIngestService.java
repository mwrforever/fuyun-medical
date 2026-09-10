package com.fuyun.iot.service;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import java.util.List;

/**
 * 遥测入库服务：AMQP 主链路与 HTTP 兜底通道共用的批量落库入口（M14 FU-M14-05 遥测管道统一语言）。
 *
 * <p>落库语义：绑定快照冗余（patient_id/visit_id 写入时快照，14-iot §3.3）+ 批量写唯一约束冲突
 * 忽略（明细层幂等载体，uk_iot_telemetry_device_metric_time）；方法级独立事务
 * （宪法 A.4.2-7"遥测批量落库按 500-5000 条/批独立事务"）。调用方为攒批器（internal，
 * B4.2 任务 B）与兜底 controller（B4.3）。
 */
public interface ITelemetryIngestService {

    /**
     * 批量落库一攒批遥测消息。
     *
     * <p>执行流程：按 deviceId 一次批量 in 查询 BOUND 绑定快照（拒 N+1）→ 冗余 patient_id/visit_id
     * （无绑定落 NULL）→ mapper 多值 INSERT ON CONFLICT DO NOTHING。批内单行 value 不可数值定型时
     * 跳过该行并告警（NUMERIC NOT NULL 列物理约束，跳过不阻断批次，javadoc 详见实现类）。
     *
     * @param batch 标准遥测消息批次，非空；来源：攒批器（解析器产物，quality/source 已校验值域）；
     *              允许空列表（直接返回 0 不触库）
     * @return 实际插入行数（唯一键冲突被忽略的行不计入）——即本次真实新增的遥测行数，
     *         供攒批确认语义与计数指标使用；空批次返回 0
     */
    int ingest(List<StandardTelemetryMessage> batch);
}
