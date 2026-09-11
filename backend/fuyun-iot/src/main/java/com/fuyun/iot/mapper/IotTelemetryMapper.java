package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotTelemetryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 遥测明细 mapper：批量写唯一入口（超表行只增，应用层零 UPDATE/DELETE）。
 *
 * <p>复杂 SQL 走 mapper + XML（resources/mapper/IotTelemetryMapper.xml，宪法 A.4.3-15）——
 * 多值 INSERT + ON CONFLICT DO NOTHING 无法用链式 wrapper 表达；冲突忽略即写入幂等载体
 * （唯一约束 uk_iot_telemetry_device_metric_time，14-iot §3.1）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotTelemetryMapper extends BaseMapper<IotTelemetryEntity> {

    /**
     * 批量插入遥测行，唯一键冲突行忽略（INSERT ... ON CONFLICT DO NOTHING）。
     *
     * @param batch 遥测实体批次，非空且非空列表（500-5000 条/批，独立事务由调用方 service 承担）
     * @return 实际插入行数（冲突被忽略的行不计入——返回值即本次真实落库行数，
     *         供攒批确认语义与计数指标使用）
     */
    int insertBatchIgnoreConflict(@Param("batch") List<IotTelemetryEntity> batch);
}
