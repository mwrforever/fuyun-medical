package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.service.IDeviceStatusService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 设备状态服务实现（iot.iot_device 状态机执行点，BRIEF-PR4-01 §1.5 状态帧处理契约）。
 *
 * <p>更新形态：存在性查询 + lambdaUpdate 精确投影（禁整实体 UPDATE，仅写 status 与按状态分派的
 * 时间字段——ONLINE→last_online_at、OFFLINE→last_offline_at，INACTIVE/ABNORMAL/DISABLED 不更新
 * 时间字段，14-iot §5 时间字段分派语义）。设备不存在（档案未注册/已注销）info 日志跳过返回
 * null——未注册设备的状态帧不产生任何副作用，静默跳过而非报错是状态机的容错口径。
 *
 * <p>Redis 快照面（P2 PR-2 Task 5 补齐，GC13）：状态机更新成功后同步写
 * {@code fy:iot:snapshot:device-status:{deviceId}}（五态 code + lastOnlineAt），String JSON
 * 承载（StringRedisTemplate，禁 JDK 序列化），TTL 24 小时（≥2×采集周期，禁无 TTL 键）——
 * 病区设备墙/大屏读快照不触库。写失败仅 warn 降级不阻断状态机主链（缓存可重建，DB 为权威）。
 *
 * <p>不发事件：本类只落库与快照；事件发布由调用方（消费者）在本方法返回档案 wardId（非 null）后，
 * 以该 wardId 补全事件载荷再经 IotEventPublisher 执行（事务外调用 + Confirm/Returns 回调，
 * 宪法 A.4.2-7"事务内禁消息发送"）。装配归 IotConfig @Import；JaCoCo 核心包
 * （com.fuyun.iot.service.impl）LINE=1.00 成员。
 */
@Slf4j
public class DeviceStatusServiceImpl implements IDeviceStatusService {

    /** 快照键前缀：fy:iot:snapshot:device-status:（A.5-1 命名，拼 deviceId 为完整键） */
    private static final String SNAPSHOT_KEY_PREFIX = "fy:iot:snapshot:device-status:";

    /** 快照键 TTL：24 小时（GC13 快照类键 ≥2×采集周期；过期由 Redis 兜底免定时清理） */
    private static final Duration SNAPSHOT_TTL = Duration.ofHours(24);

    /** 设备档案数据访问：存在性查询与状态机条件更新 */
    private final IotDeviceMapper deviceMapper;

    /** String 模板（禁 JDK 序列化）：设备状态快照写 Redis 唯一出口 */
    private final StringRedisTemplate redisTemplate;

    /** JSON 转换器：快照五态+lastOnlineAt 序列化（Boot 容器实例，jsr310 时间 ISO 串形态） */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param deviceMapper  设备档案 mapper，非空；来源：同模块 mapper 包
     * @param redisTemplate String 模板，非空；来源：Boot Redis 自动配置
     * @param objectMapper  JSON 转换器，非空；来源：Boot 容器 ObjectMapper
     */
    public DeviceStatusServiceImpl(
            IotDeviceMapper deviceMapper, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.deviceMapper = deviceMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Long apply(DeviceStatusEvent event) {
        // 存在性查询（精确投影 device_id/status/ward_id/last_online_at：status 供状态变更日志的
        // 旧值观测，ward_id 供消费侧补全状态事件推送路由，last_online_at 供非 ONLINE 快照保留
        // 历史值——同一数据行一次取回，不二次触库）
        IotDeviceEntity device = deviceMapper.selectOne(Wrappers.<IotDeviceEntity>lambdaQuery()
                .eq(IotDeviceEntity::getDeviceId, event.deviceId())
                .select(
                        IotDeviceEntity::getDeviceId,
                        IotDeviceEntity::getStatus,
                        IotDeviceEntity::getWardId,
                        IotDeviceEntity::getLastOnlineAt));
        if (device == null) {
            // 未注册/已注销设备的状态帧跳过不报错（14-iot §5 发布点容错口径）
            log.info("设备状态帧跳过（设备档案不存在）：deviceId={}，status={}", event.deviceId(), event.status());
            return null;
        }
        // lambdaUpdate 精确投影：仅 status + 按状态分派的时间字段（禁整实体 UPDATE，宪法 A.4.3-14）
        LambdaUpdateWrapper<IotDeviceEntity> update = Wrappers.<IotDeviceEntity>lambdaUpdate()
                .eq(IotDeviceEntity::getDeviceId, event.deviceId())
                .set(IotDeviceEntity::getStatus, event.status());
        // 时间字段分派（14-iot §5）：仅 ONLINE/OFFLINE 更新对应最近上下线时刻，其余状态不动时间字段
        if (event.status() == DeviceStatus.ONLINE) {
            update.set(IotDeviceEntity::getLastOnlineAt, toUtc(event.occurredAt()));
        } else if (event.status() == DeviceStatus.OFFLINE) {
            update.set(IotDeviceEntity::getLastOfflineAt, toUtc(event.occurredAt()));
        }
        int updated = deviceMapper.update(null, update);
        if (updated == 0) {
            // 条件更新未命中：并发删除/逻辑删竞态（P0 无删除端点，属极端场景），告警并按无效设备处置
            log.warn("设备状态更新未命中（并发竞态或档案刚被删除）：deviceId={}，status={}", event.deviceId(), event.status());
            return null;
        }
        log.info(
                "设备状态变更完成：deviceId={}，{}→{}，wardId={}，occurredAt={}",
                event.deviceId(),
                device.getStatus(),
                event.status(),
                device.getWardId(),
                event.occurredAt());
        // 状态机更新成功后刷新设备状态快照（缓存面，失败降级不影响主链）
        writeStatusSnapshot(
                event.deviceId(),
                event.status(),
                event.status() == DeviceStatus.ONLINE ? toUtc(event.occurredAt()) : device.getLastOnlineAt());
        // 返回档案病区 ID 供消费侧补全状态事件（未编病区设备为 null，消费侧按无效语义不发布）
        return device.getWardId();
    }

    /**
     * 写设备状态快照（fy:iot:snapshot:device-status:{deviceId}，TTL 24h）：五态 code +
     * lastOnlineAt 的 String JSON 承载。Redis 故障/序列化异常仅 warn 降级——快照可由下一次
     * 状态帧重建，不反向阻断状态机主链（DB 为权威数据源）。
     *
     * @param deviceId      设备标识，非空；来源：状态帧 deviceId 字段
     * @param status        变更后状态（五态值域），非空
     * @param lastOnlineAt  快照承载的最近上线时刻：ONLINE 取本次发生时刻、其余状态保留档案
     *                      历史值（避免非 ONLINE 帧把快照历史上线时刻清空），可空（未上线过）
     */
    private void writeStatusSnapshot(String deviceId, DeviceStatus status, OffsetDateTime lastOnlineAt) {
        try {
            String json = objectMapper.writeValueAsString(new DeviceStatusSnapshot(deviceId, status, lastOnlineAt));
            // 显式 TTL 写入（禁无 TTL 键红线）；String JSON 承载（禁 JDK 序列化红线）
            redisTemplate.opsForValue().set(SNAPSHOT_KEY_PREFIX + deviceId, json, SNAPSHOT_TTL);
        } catch (Exception e) {
            log.warn("设备状态快照写入失败（缓存降级不影响状态机）：deviceId={}，原因={}", deviceId, e.getMessage());
        }
    }

    /**
     * 设备状态快照 JSON 载体（record 不可变）：病区设备墙/大屏读面的最小投影。status 序列化
     * 输出五态 code（DeviceStatus @JsonValue），lastOnlineAt 输出 ISO-8601 串（jsr310 模块）。
     *
     * @param deviceId     设备标识，非空
     * @param status       当前状态（INACTIVE/ONLINE/OFFLINE/ABNORMAL/DISABLED 五态值域），非空
     * @param lastOnlineAt 最近上线时刻，可空（未上线过设备为 null，序列化省略）
     */
    record DeviceStatusSnapshot(String deviceId, DeviceStatus status, OffsetDateTime lastOnlineAt) {}

    /**
     * 状态发生时刻（UTC Instant）转 OffsetDateTime（last_online_at/last_offline_at TIMESTAMPTZ 列载体）。
     *
     * @param occurredAt 状态发生时刻，非空；来源：状态帧解析产物（ISO-8601 已定型）
     * @return UTC OffsetDateTime，非空
     */
    private static OffsetDateTime toUtc(Instant occurredAt) {
        return OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
    }
}
