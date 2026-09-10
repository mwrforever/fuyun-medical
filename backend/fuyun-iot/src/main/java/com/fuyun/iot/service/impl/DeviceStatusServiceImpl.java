package com.fuyun.iot.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.service.IDeviceStatusService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.extern.slf4j.Slf4j;

/**
 * 设备状态服务实现（iot.iot_device 状态机执行点，BRIEF-PR4-01 §1.5 状态帧处理契约）。
 *
 * <p>更新形态：存在性查询 + lambdaUpdate 精确投影（禁整实体 UPDATE，仅写 status 与按状态分派的
 * 时间字段——ONLINE→last_online_at、OFFLINE→last_offline_at，INACTIVE/ABNORMAL/DISABLED 不更新
 * 时间字段，14-iot §5 时间字段分派语义）。设备不存在（档案未种子/未注册）info 日志跳过返回
 * false——P0 无设备注册 API，档案由数据种子提供，静默跳过而非报错是状态机的容错口径。
 *
 * <p>不发事件：本类只落库；事件发布由调用方（消费者）在返回 true 后经 IotEventPublisher 执行
 * （事务外调用 + Confirm/Returns 回调，宪法 A.4.2-7"事务内禁消息发送"）。
 * 装配归 IotConfig @Import；JaCoCo 核心包（com.fuyun.iot.service.impl）LINE=1.00 成员。
 */
@Slf4j
public class DeviceStatusServiceImpl implements IDeviceStatusService {

    /** 设备档案数据访问：存在性查询与状态机条件更新 */
    private final IotDeviceMapper deviceMapper;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param deviceMapper 设备档案 mapper，非空；来源：同模块 mapper 包
     */
    public DeviceStatusServiceImpl(IotDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    @Override
    public boolean apply(DeviceStatusEvent event) {
        // 存在性查询（精确投影 device_id/status：status 供状态变更日志的旧值观测）
        IotDeviceEntity device = deviceMapper.selectOne(Wrappers.<IotDeviceEntity>lambdaQuery()
                .eq(IotDeviceEntity::getDeviceId, event.deviceId())
                .select(IotDeviceEntity::getDeviceId, IotDeviceEntity::getStatus));
        if (device == null) {
            // P0 档案由数据种子提供：未注册设备的状态帧跳过不报错（14-iot §5 发布点容错口径）
            log.info("设备状态帧跳过（设备档案不存在）：deviceId={}，status={}", event.deviceId(), event.status());
            return false;
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
            return false;
        }
        log.info(
                "设备状态变更完成：deviceId={}，{}→{}，occurredAt={}",
                event.deviceId(),
                device.getStatus(),
                event.status(),
                event.occurredAt());
        return true;
    }

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
