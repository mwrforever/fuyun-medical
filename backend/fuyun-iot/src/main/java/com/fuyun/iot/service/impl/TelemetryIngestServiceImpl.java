package com.fuyun.iot.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.service.ITelemetryIngestService;
import com.fuyun.iot.service.ITelemetryPushService;
import com.fuyun.iot.vo.BindingVO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 遥测入库服务实现（iot.iot_telemetry 批量写唯一入口，BRIEF-PR4-01 §3 service 行）。
 *
 * <p>写路径：绑定快照经绑定域 {@link IBindingService#findActiveByDevice} 按 distinct 设备去重消费
 * （批内同设备多帧只查一次；单点口径复用绑定管理域查询，禁旁路快照查询）→ 冗余 patient_id/visit_id
 * （写入时快照，无绑定落 NULL——遥测仍入库仅无患者归属，14-iot §3.3 "消毒/未绑定场景设备数据
 * 标'未关联'仍入库"）→ mapper 多值 INSERT ON CONFLICT DO NOTHING（唯一约束冲突忽略 = 明细层幂等，
 * 返回实际插入行数）。方法级独立事务（宪法 A.4.2-7）。
 *
 * <p>B4.3 摘要推送接线：落库成功后按绑定快照病区分组，每组经 {@link ITelemetryPushService}
 * 推一帧遥测摘要（/topic/iot/telemetry/{wardId}，简报 §1.4"遥测批量落库成功后推一帧汇总；
 * 无绑定快照的帧不推送仅落库"）。推送时机遵守宪法 A.4.2-7"事务内禁止远程调用、消息发送与
 * 人工等待，对外调用在事务提交后执行"：分组数据在事务方法内组装完成，推送 I/O 经
 * TransactionSynchronizationManager 注册 afterCommit 回调延迟至<b>事务提交后</b>执行（回滚
 * 事务不推送——摘要只对已提交批次负责）；无事务同步上下文（单测直调等未经代理场景）时直接
 * 推送，行为不变。推送失败仅 error 告警不回滚落库批次——落库是主职责、推送是辅助语义。
 *
 * <p>W-7 非数值承载语义（D-9 裁决，V1005 raw_value 列）：整批全量入库不再丢弃任何行——value 可
 * 数值定型的行落 NUMERIC 值列且 raw_value 为 NULL；非数值行 value 落 NULL（哨兵值会污染生理指标
 * 统计，禁回填）、raw_value 承载原文（标量原文；对象/数组经 Jackson 树规整为紧凑 JSON 标准输出）
 * 且 quality 强制 BAD（语义 = 非数值定型标注，不阻断入库）。批次观测口径为
 * received/inserted/unbound/non_numeric_bad 四计数（历史 skipped_non_numeric 丢弃口径已退役）。
 *
 * <p>装配归 IotConfig @Import（com.fuyun.iot 不在组件扫描范围，宪法 B.1/B.4.2-12）。
 * JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员，单测全覆盖。
 */
@Slf4j
public class TelemetryIngestServiceImpl implements ITelemetryIngestService {

    /** 紧凑 JSON 规整器（线程安全，树读入 + 标准写出去除原文内部空白；无 Boot 定制依赖，静态持有） */
    private static final ObjectMapper COMPACT_JSON_MAPPER = new ObjectMapper();

    /** 绑定域服务：生效绑定查询唯一出口（findActiveByDevice，遥测富化与设备归属查询同源） */
    private final IBindingService bindingService;

    /** 遥测明细数据访问：多值 INSERT ON CONFLICT DO NOTHING 唯一写通道 */
    private final IotTelemetryMapper telemetryMapper;

    /** STOMP 推送服务：落库成功后按病区分组推送摘要帧（/topic/iot/telemetry/{wardId}） */
    private final ITelemetryPushService pushService;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param bindingService  绑定域服务，非空；来源：同模块 service 装配链
     * @param telemetryMapper 遥测明细 mapper，非空；来源：同模块 mapper 包
     * @param pushService     STOMP 推送服务，非空；来源：IotConfig 装配链
     */
    public TelemetryIngestServiceImpl(
            IBindingService bindingService, IotTelemetryMapper telemetryMapper, ITelemetryPushService pushService) {
        this.bindingService = bindingService;
        this.telemetryMapper = telemetryMapper;
        this.pushService = pushService;
    }

    @Override
    @Transactional
    public int ingest(List<StandardTelemetryMessage> batch) {
        // 空批次防御：直接返回零行（避免空 IN 列表与空 VALUES 生成非法 SQL）
        if (batch.isEmpty()) {
            return 0;
        }
        // 绑定快照按 distinct 设备去重消费绑定域查询：批内同设备多帧仅一次查询（禁逐行查询），
        // 只取 BOUND 生效绑定，patient/visit/ward 供快照冗余与摘要推送分组
        List<String> deviceIds = batch.stream()
                .map(StandardTelemetryMessage::deviceId)
                .distinct()
                .toList();
        Map<String, BindingVO> boundByDeviceId = new HashMap<>(deviceIds.size());
        for (String deviceId : deviceIds) {
            bindingService.findActiveByDevice(deviceId).ifPresent(vo -> boundByDeviceId.put(deviceId, vo));
        }

        // 全量组装实体（W-7：非数值行不再丢弃，raw_value 承载原文 + quality 强制 BAD 标注）
        List<IotTelemetryEntity> entities = new ArrayList<>(batch.size());
        long nonNumericBadCount = 0;
        for (StandardTelemetryMessage message : batch) {
            IotTelemetryEntity entity = toEntity(message, boundByDeviceId.get(message.deviceId()));
            if (entity.getValue() == null) {
                // 非数值定型行计数（quality 已强制 BAD；日志观测口径 non_numeric_bad）
                nonNumericBadCount++;
            }
            entities.add(entity);
        }
        // 数据库写操作：批量写唯一键冲突行忽略，返回实际插入行数（冲突行不计入 = 明细层幂等语义）
        int inserted = telemetryMapper.insertBatchIgnoreConflict(entities);
        log.info(
                "遥测批量落库完成：received={}，inserted={}，unbound={}，non_numeric_bad={}",
                batch.size(),
                inserted,
                countUnbound(entities),
                nonNumericBadCount);
        // 摘要推送分组数据在事务方法内组装完成（快照 wardId 分组），推送 I/O 移出事务执行——
        // 宪法 A.4.2-7"事务内禁止远程调用、消息发送与人工等待，对外调用在事务提交后执行"
        Map<Long, List<IotTelemetryEntity>> writtenByWardId = groupWrittenByWard(entities, boundByDeviceId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务同步激活（经 Spring 代理调用）：注册 afterCommit 回调，事务提交后才执行推送；
            // 回调运行于提交线程且不新开事务（SimpleBroker 进程内直推，无二次远程调用）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    pushSummariesByWard(writtenByWardId);
                }
            });
        } else {
            // 无事务同步上下文（单测直调等未经代理场景）：无事务可出，行为不变直接推送
            pushSummariesByWard(writtenByWardId);
        }
        return inserted;
    }

    /**
     * 按绑定快照病区分组本批已写入实体（纯内存组装，无 I/O，供推送时机分派复用）。
     *
     * <p>分组语义：wardId 取设备 BOUND 绑定的写入时快照（iot_binding.ward_id），无绑定或绑定
     * 未编病区的行仅落库不入组；每组一帧（条数/items/occurredAt 上界见推送服务载荷契约）。
     *
     * @param entities        已组装并落库的遥测实体批次，非空
     * @param boundByDeviceId 绑定快照映射（deviceId → BOUND 绑定视图，含 wardId），非空
     * @return 病区 → 本批该病区已写入实体列表（值列表非空），非空；无归属行不出现
     */
    private static Map<Long, List<IotTelemetryEntity>> groupWrittenByWard(
            List<IotTelemetryEntity> entities, Map<String, BindingVO> boundByDeviceId) {
        Map<Long, List<IotTelemetryEntity>> writtenByWardId = new HashMap<>();
        for (IotTelemetryEntity entity : entities) {
            BindingVO binding = boundByDeviceId.get(entity.getDeviceId());
            Long wardId = binding == null ? null : binding.wardId();
            if (wardId != null) {
                // 按病区分组：同病区多设备/多帧合并为一帧摘要（每批每病区一帧，简报 §1.4 推送频率口径）
                writtenByWardId
                        .computeIfAbsent(wardId, key -> new ArrayList<>())
                        .add(entity);
            }
        }
        return writtenByWardId;
    }

    /**
     * 执行按病区分组的摘要帧推送（推送 I/O 执行点：调用方须保证已处于事务提交后或无事务上下文）。
     *
     * <p>推送失败仅 error 告警不中断其余病区批次——推送是辅助语义，落库批次与客户端确认语义
     * 不受影响（broker 抖动不阻断遥测持久化，失败帧靠唯一约束重推兜底）。
     *
     * @param writtenByWardId 病区 → 已写入实体列表分组（{@link #groupWrittenByWard} 产物），非空
     */
    private void pushSummariesByWard(Map<Long, List<IotTelemetryEntity>> writtenByWardId) {
        writtenByWardId.forEach((wardId, written) -> {
            try {
                pushService.pushSummary(written, wardId);
            } catch (RuntimeException e) {
                // 推送失败吞并：错误留痕后批次照常返回（辅助语义不阻断主链路，javadoc 声明）
                log.error("遥测摘要推送失败（不影响落库批次）：wardId={}，count={}，原因={}", wardId, written.size(), e.getMessage(), e);
            }
        });
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
     * @return 数值定型结果；不可解析返回 null（调用方落 raw_value 原文承载）
     */
    private static BigDecimal parseValue(StandardTelemetryMessage message) {
        try {
            return new BigDecimal(message.value());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 非数值原文承载规整（W-7）：标量以原文承载；形似对象/数组的原文经 Jackson 树读入 + 标准写出
     * 规整为紧凑 JSON（去除键值/元素间空白，无空格标准输出）。入参由 CF-7 契约保证非空
     * （StandardTelemetryMessage.value 非空），且本方法仅在 parseValue 判定不可数值定型后调用
     * （null 入参会在定型处先行暴露，此处不重复防御）。
     *
     * @param raw 遥测原文，非空（CF-7 契约）
     * @return 承载文本：对象/数组为紧凑 JSON，其余为原文
     */
    private static String toRawValueText(String raw) {
        String trimmed = raw.trim();
        boolean shapedAsJson = (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
        if (!shapedAsJson) {
            // 标量原文：原样承载（quality=BAD 已完成非数值定型标注）
            return raw;
        }
        try {
            // 形似对象/数组：树规整为紧凑 JSON（标准输出无空格，规整重复上报的格式漂移）
            JsonNode node = COMPACT_JSON_MAPPER.readTree(trimmed);
            return COMPACT_JSON_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // 形似 JSON 但非法（如 "{"a": }"）：按标量原文承载，不因规整失败丢行（quality=BAD 兜底标注）
            return raw;
        }
    }

    /**
     * 标准遥测消息 → 超表行实体（枚举 code 已由解析器校验值域，fromCode 不会失败）。
     *
     * <p>W-7 分流：value 可数值定型 → NUMERIC 值列、raw_value 保持 NULL、quality 取消息原值；
     * 不可数值定型 → value 落 NULL、raw_value 承载原文/紧凑 JSON、quality 强制 BAD（非数值定型
     * 标注，不阻断入库）。
     *
     * @param message 标准遥测消息，非空
     * @param binding 该设备的 BOUND 绑定快照，可为 null（无绑定：患者/就诊列落 NULL）
     * @return 遥测实体，非空
     */
    private static IotTelemetryEntity toEntity(StandardTelemetryMessage message, BindingVO binding) {
        IotTelemetryEntity entity = new IotTelemetryEntity();
        entity.setDeviceId(message.deviceId());
        entity.setPatientId(binding == null ? null : binding.patientId());
        entity.setVisitId(binding == null ? null : binding.visitId());
        entity.setMetricCode(message.metricCode());
        BigDecimal value = parseValue(message);
        entity.setValue(value);
        if (value == null) {
            // 非数值行：raw_value 承载原文 + quality 强制 BAD（W-7 定型标注语义，不阻断入库）
            entity.setRawValue(toRawValueText(message.value()));
            entity.setQuality(TelemetryQuality.BAD);
        } else {
            entity.setQuality(TelemetryQuality.fromCode(message.quality()));
        }
        entity.setUnit(message.unit());
        entity.setOccurredAt(OffsetDateTime.ofInstant(message.occurredAt(), ZoneOffset.UTC));
        entity.setSource(TelemetrySource.fromCode(message.source()));
        return entity;
    }
}
