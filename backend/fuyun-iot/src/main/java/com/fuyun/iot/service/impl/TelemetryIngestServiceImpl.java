package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.service.ITelemetryIngestService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 遥测入库服务实现（iot.iot_telemetry 批量写唯一入口，BRIEF-PR4-01 §3 service 行）。
 *
 * <p>写路径：绑定快照按 deviceId 单次批量 in 查询（只取 BOUND，拒 N+1，宪法 A.4.3-14）→
 * 冗余 patient_id/visit_id（写入时快照，无绑定落 NULL——遥测仍入库仅无患者归属，14-iot §3.3
 * "消毒/未绑定场景设备数据标'未关联'仍入库"）→ mapper 多值 INSERT ON CONFLICT DO NOTHING
 * （唯一约束冲突忽略 = 明细层幂等，返回实际插入行数）。方法级独立事务（宪法 A.4.2-7）。
 *
 * <p>value 定型语义：CF-7 value 为字符串载体，落 NUMERIC NOT NULL 列前经 BigDecimal 解析；
 * 不可解析行（解析器已标 BAD 保留原文）跳过并整批汇总告警——跳过理由：NOT NULL 列无法承载
 * 非数值原文，哨兵值（如 0）会污染生理指标统计，跳过是"标注不阻断"约束下的批次无损选项
 * （BAD 行本就供质量统计口径，不参与有效值分析）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1/B.4.2-12）。
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class TelemetryIngestServiceImpl implements ITelemetryIngestService {

    /** 绑定快照数据访问：BOUND 绑定的批量 in 查询（跨表查询按 A.4.3-13 用 Wrappers 静态工厂） */
    private final IotBindingMapper bindingMapper;

    /** 遥测明细数据访问：多值 INSERT ON CONFLICT DO NOTHING 唯一写通道 */
    private final IotTelemetryMapper telemetryMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param bindingMapper   设备绑定 mapper，非空；来源：同模块 mapper 包
     * @param telemetryMapper 遥测明细 mapper，非空；来源：同模块 mapper 包
     */
    public TelemetryIngestServiceImpl(IotBindingMapper bindingMapper, IotTelemetryMapper telemetryMapper) {
        this.bindingMapper = bindingMapper;
        this.telemetryMapper = telemetryMapper;
    }

    @Override
    @Transactional
    public int ingest(List<StandardTelemetryMessage> batch) {
        // 空批次防御：直接返回零行（避免空 IN 列表与空 VALUES 生成非法 SQL）
        if (batch.isEmpty()) {
            return 0;
        }
        // 绑定快照一次批量查询（distinct 去重防同设备多帧撑大 in 列表；只取 BOUND 精确投影三列）
        List<String> deviceIds = batch.stream()
                .map(StandardTelemetryMessage::deviceId)
                .distinct()
                .toList();
        Map<String, IotBindingEntity> boundByDeviceId = bindingMapper
                .selectList(Wrappers.<IotBindingEntity>lambdaQuery()
                        .in(IotBindingEntity::getDeviceId, deviceIds)
                        .eq(IotBindingEntity::getStatus, BindingStatus.BOUND)
                        .select(
                                IotBindingEntity::getDeviceId,
                                IotBindingEntity::getPatientId,
                                IotBindingEntity::getVisitId))
                .stream()
                .collect(Collectors.toMap(IotBindingEntity::getDeviceId, Function.identity()));

        List<IotTelemetryEntity> entities = new ArrayList<>(batch.size());
        List<String> unparsableValueKeys = new ArrayList<>();
        for (StandardTelemetryMessage message : batch) {
            BigDecimal value = parseValue(message);
            if (value == null) {
                // 非数值行跳过不入库（整批汇总一次告警，禁循环内逐行打日志）
                unparsableValueKeys.add(message.deviceId() + "/" + message.metricCode());
                continue;
            }
            // 绑定快照冗余：无绑定设备落 NULL（"未关联仍入库"口径），实体转换见 toEntity
            IotBindingEntity binding = boundByDeviceId.get(message.deviceId());
            entities.add(toEntity(message, value, binding));
        }
        if (!unparsableValueKeys.isEmpty()) {
            log.warn(
                    "遥测批次存在不可数值定型的行已跳过：skipped={}，devices/metrics={}", unparsableValueKeys.size(), unparsableValueKeys);
        }
        // 批量写：唯一键冲突行忽略，返回实际插入行数（冲突行不计入 = 明细层幂等语义）
        int inserted = telemetryMapper.insertBatchIgnoreConflict(entities);
        log.info(
                "遥测批量落库完成：received={}，inserted={}，unbound={}，skipped_non_numeric={}",
                batch.size(),
                inserted,
                countUnbound(entities),
                unparsableValueKeys.size());
        return inserted;
    }

    /**
     * 统计无绑定快照的行数（日志观测口径：未关联入库占比是绑定质量运营指标）。
     *
     * @param entities 已组装的遥测实体批次，非空
     * @return patient_id 为空的行数
     */
    private long countUnbound(List<IotTelemetryEntity> entities) {
        return entities.stream().filter(entity -> entity.getPatientId() == null).count();
    }

    /**
     * 解析 CF-7 value 字符串为 NUMERIC 定型值。
     *
     * @param message 标准遥测消息，非空
     * @return 数值定型结果；不可解析返回 null（调用方跳过该行）
     */
    private static BigDecimal parseValue(StandardTelemetryMessage message) {
        try {
            return new BigDecimal(message.value());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 标准遥测消息 → 超表行实体（枚举 code 已由解析器校验值域，fromCode 不会失败）。
     *
     * @param message 标准遥测消息，非空
     * @param value   已定型数值，非空
     * @param binding 该设备的 BOUND 绑定快照，可为 null（无绑定：患者/就诊列落 NULL）
     * @return 遥测实体，非空
     */
    private static IotTelemetryEntity toEntity(
            StandardTelemetryMessage message, BigDecimal value, IotBindingEntity binding) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId(message.deviceId());
        entity.setPatientId(binding == null ? null : binding.getPatientId());
        entity.setVisitId(binding == null ? null : binding.getVisitId());
        entity.setMetricCode(message.metricCode());
        entity.setValue(value);
        entity.setUnit(message.unit());
        entity.setOccurredAt(OffsetDateTime.ofInstant(message.occurredAt(), ZoneOffset.UTC));
        entity.setQuality(TelemetryQuality.fromCode(message.quality()));
        entity.setSource(TelemetrySource.fromCode(message.source()));
        return entity;
    }
}
