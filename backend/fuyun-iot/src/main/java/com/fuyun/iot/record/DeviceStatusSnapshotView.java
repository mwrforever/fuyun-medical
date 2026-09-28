package com.fuyun.iot.record;

import com.fuyun.iot.enums.DeviceStatus;
import java.time.OffsetDateTime;

/**
 * 设备状态快照读取视图（fy:iot:snapshot:device-status:{deviceId} 反序列化载体，P2 PR-2 Task 11
 * 病区设备墙消费面）：与 Task 5 写入面（DeviceStatusServiceImpl.DeviceStatusSnapshot 私有嵌套
 * record）JSON 契约同形——deviceId/status/lastOnlineAt 三字段。
 *
 * <p>record 纯数据载体（宪法 A.1-2）；status 反序列化按 DeviceStatus code（@JsonValue/@EnumValue
 * 双向）；独立读取载体而非复用写入面私有 record——跨类契约以同形 JSON 表达，写入面结构变更时
 * 读取面显式跟随（禁止跨类共享私有嵌套类型）。
 *
 * @param deviceId     设备标识，非空
 * @param status       快照状态（五态值域），非空
 * @param lastOnlineAt 快照最近上线时刻，可空（未上线过设备序列化省略）
 */
public record DeviceStatusSnapshotView(String deviceId, DeviceStatus status, OffsetDateTime lastOnlineAt) {}
