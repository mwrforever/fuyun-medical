package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 遥测明细实体（iot.iot_telemetry，V402 迁移）：TimescaleDB 超表行载体（按天分区、只增）。
 *
 * <p>主键语义定稿（BRIEF-PR4-01 §3 entity 行二选一）：本实体不标 @TableId——超表无物理主键，
 * 行唯一性由三列唯一索引 uk_iot_telemetry_device_metric_time（device_id, metric_code, occurred_at，
 * 含分区列）承载；批量写经 mapper XML 多值 INSERT + ON CONFLICT DO NOTHING（A.4.3-15），
 * 不经 MP 主键机制，标注伪主键反而误导（如 @TableId(occurred_at) 会暗示单列唯一语义）。
 * 只增口径（V302 audit_log 先例）：无审计列、无 updated_at 触发器、无 deleted（只增不更新）；
 * 应用层对本表零 UPDATE/DELETE，清理由 TimescaleDB 保留策略承担（90 天）。
 */
@Getter
@Setter
@TableName("iot.iot_telemetry")
public class IotTelemetryEntity {

    /** IoTDA 设备标识（自然键，关联 iot_device；唯一键三列之一） */
    private String deviceId;

    /** 患者 ID（写入时绑定快照，无绑定为 NULL），可空 */
    private Long patientId;

    /** 就诊 ID（写入时绑定快照，无绑定为 NULL；CF-3 定长 14 位字符串，V1006 改造），可空 */
    private String visitId;

    /** 指标编码（P0 未建 iot_metric_dict，原生编码直传；唯一键三列之一） */
    private String metricCode;

    /** 采集值（CF-7 value 字符串解析定型为 NUMERIC，仅数值定型行填写；非数值行为 NULL——原文承载见 rawValue） */
    private BigDecimal value;

    /**
     * 非数值遥测文本承载（W-7，V1005 列）：非数值标量为原文、对象/数组为紧凑 JSON（Jackson 标准
     * 输出无空格）；数值定型行为 NULL。哨兵值会污染生理指标统计，故原文独立成列而非回填 value。
     */
    private String rawValue;

    /** 计量单位（无量纲指标为空），可空 */
    private String unit;

    /** 采集发生时刻（分区键、按天 chunk；唯一键三列之一，写入幂等时间锚点） */
    private OffsetDateTime occurredAt;

    /** 数据质量：GOOD/SUSPECT/BAD（值域 = CF-7；标注不丢弃口径） */
    private TelemetryQuality quality;

    /** 接入链路来源：IOTDA/HL7（值域 = CF-7；P0 全部 IOTDA） */
    private TelemetrySource source;
}
