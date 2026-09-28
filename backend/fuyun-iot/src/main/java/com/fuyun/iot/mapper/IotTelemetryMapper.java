package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.api.TelemetryPoint;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.record.DailyQualityCountRow;
import com.fuyun.iot.record.DeviceMetricLastRow;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 遥测明细 mapper：批量写唯一入口（超表行只增，应用层零 UPDATE/DELETE）与时序查询读面
 * （FU-M14-06 查询路由 + FU-M14-11 质量统计，P2 PR-2 Task 10）。
 *
 * <p>复杂 SQL 走 mapper + XML（resources/mapper/IotTelemetryMapper.xml，宪法 A.4.3-15）——
 * 多值 INSERT + ON CONFLICT DO NOTHING 无法用链式 wrapper 表达；冲突忽略即写入幂等载体
 * （唯一约束 uk_iot_telemetry_device_metric_time，14-iot §3.1）。时序查询投影经构造器映射到
 * {@link TelemetryPoint}（api 契约 record）。
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

    /**
     * raw 明细档时序查询（路由档位一：≤24h 走明细，14-iot §3.2）：单指标时窗内明细点按
     * occurred_at 升序输出，五聚合值恒等该行采集值、sampleCount=1（明细点即未降采样桶）。
     *
     * <p>三个范围条件互斥按需传入（服务层路由产物）：deviceId 单设备 / patientId 患者维度
     * （idx_iot_telemetry_patient 准入）/ deviceIds 设备集合（病区或患者展开面）；全空返回空。
     *
     * @param metricCode 指标编码，非空
     * @param from       起始时刻（含），非空
     * @param to         结束时刻（不含），非空
     * @param deviceId   设备标识，可空（单设备条件）
     * @param patientId  患者 ID，可空（患者维度条件）
     * @param deviceIds  设备集合，可空（病区/患者展开集合条件；空集合视为无此条件）
     * @return 时序点清单（time 升序），非空；无数据为空清单
     */
    List<TelemetryPoint> selectRawSeries(
            @Param("metricCode") String metricCode,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("deviceId") String deviceId,
            @Param("patientId") Long patientId,
            @Param("deviceIds") Collection<String> deviceIds);

    /**
     * 连续聚合档时序查询（路由档位二/三：超 24h 或显式降采样走 cagg、超 90 天强制 cagg_1h）：
     * 聚合桶按 bucket 升序输出，实时查询语义下未物化窗口由 TimescaleDB 自动联合明细（V1011）。
     *
     * @param tableName  连续聚合视图全名（iot.cagg_1min / iot.cagg_1h）；SQL 无法参数化表名，
     *                   经 ${} 拼接——取值仅来自服务层路由枚举白名单，禁接用户输入（注入防线）
     * @param metricCode 指标编码，非空
     * @param from       起始时刻（含，对齐桶起点），非空
     * @param to         结束时刻（不含），非空
     * @param deviceIds  设备集合，非空（cagg 无患者/病区列，患者与病区维度由服务层先行展开）
     * @return 时序点清单（time=桶起点升序），非空；无数据为空清单
     */
    List<TelemetryPoint> selectCaggSeries(
            @Param("tableName") String tableName,
            @Param("metricCode") String metricCode,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            @Param("deviceIds") Collection<String> deviceIds);

    /**
     * 设备集合内各设备×指标末次有效采集时刻（FU-M14-11 断流判定数据源，批级一次 GROUP BY
     * 聚合下推，禁循环内单查）。
     *
     * @param deviceIds 设备集合，非空且非空列表
     * @return 设备+指标 → 末次采集时刻投影清单，非空；无采集历史的设备不出现在结果中
     *         （调用方以缺行表达"从未上报"）
     */
    List<DeviceMetricLastRow> selectLastOccurredByDeviceMetric(@Param("deviceIds") Collection<String> deviceIds);

    /**
     * 单设备单日质量计数（FU-M14-11 日统计数据源）：接收总数与异常值数（quality != GOOD）一次
     * 聚合下推数据库。
     *
     * @param deviceId 设备标识，非空
     * @param from     统计日起始时刻（含，UTC 日切），非空
     * @param to       统计日结束时刻（不含），非空
     * @return 计数投影（无数据行为 totalCount=0/anomalyCount=0），非空
     */
    DailyQualityCountRow countDailyQuality(
            @Param("deviceId") String deviceId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
