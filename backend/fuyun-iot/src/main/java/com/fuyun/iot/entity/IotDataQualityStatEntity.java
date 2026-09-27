package com.fuyun.iot.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 遥测数据质量日统计实体（iot.iot_data_quality_stat，V1012 迁移）：FU-M14-11 统计主体，按
 * 设备×自然日聚合缺数率/异常值数/质量得分（14-iot §4，供 M15 利用率分析与质量看板）。
 *
 * <p>UPSERT 载体（V1012 文件头）：统计随查询/定时惰性重算，当日行按 uk_iot_data_quality_stat_
 * device_date 冲突覆盖（ON CONFLICT DO UPDATE）；挂 updated_at 触发器（有更新路径），无 deleted
 * （统计行无逻辑删语义）。期望值推算口径：expected_count = 标称频率 × 在线分钟数（未登记标称
 * 频率记 0，缺数率恒 0 防误报）。
 */
@Getter
@Setter
@TableName("iot.iot_data_quality_stat")
public class IotDataQualityStatEntity {

    /** IoTDA 设备标识（自然键，UK 两列之一） */
    private String deviceId;

    /** 统计归属自然日（UTC 日切，UK 两列之一） */
    private LocalDate statDate;

    /** 期望采样数（标称频率×在线分钟数推算；未登记标称频率为 0） */
    private Long expectedCount;

    /** 实际接收采样数（iot_telemetry 当日该设备行数） */
    private Long receivedCount;

    /** 缺数率 0~1（期望为 0 时恒 0） */
    private BigDecimal missingRate;

    /** 异常值数（当日 quality != GOOD 行数：SUSPECT+BAD） */
    private Long anomalyCount;

    /** 质量得分 0~100（100×(1-缺数率)×(1-异常率)） */
    private BigDecimal qualityScore;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;

    /** 更新时间：数据库触发器统一维护（V1 公共函数），应用层禁止写入 */
    private OffsetDateTime updatedAt;

    /** 创建人：系统操作为 'system'（数据库默认值） */
    private String createdBy;

    /** 更新人：同 createdBy 口径 */
    private String updatedBy;
}
