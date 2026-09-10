package com.fuyun.iot.api;

import com.fuyun.iot.enums.DeviceStatus;
import java.time.Instant;

/**
 * 设备状态变更事件对象（iot.device.status-changed 载荷，V403 已登记 event_registry）。
 *
 * <p>落 api 包为宪法 B.3-1 明文（事件对象定义在发布方 api 包契约化）：发布侧（IotEventPublisher
 * 经 fy.topic 信封载荷）与消费侧（状态帧解析器产物、IDeviceStatusService 入参）共用同一语言。
 * record 纯数据载体（宪法 A.1-2 透明浅不可变），equals/hashCode 直接采用 record 语义。
 *
 * <p>P0 占位载荷契约（V403 payload_desc）：deviceId/status/occurredAt/wardId 四字段，
 * 正式契约随 P1 设备状态管理冻结；status 序列化输出枚举 code（@JsonValue）。
 *
 * @param deviceId   IoTDA 设备标识，非空；来源：状态帧 deviceId 字段
 * @param status     变更后设备状态，非空；来源：状态帧 status 字段（∈ DeviceStatus 值域，
 *                   解析期已校验）
 * @param occurredAt 状态发生时刻（UTC 语义），非空；来源：状态帧 occurredAt 字段（ISO-8601
 *                   解析定型）
 * @param wardId     病区 ID，可空（P0 状态帧契约不含 wardId，解析产物恒 null；载荷字段为
 *                   P1 推送路由与订阅方预留）；来源：设备档案或状态帧扩展字段
 */
public record DeviceStatusEvent(String deviceId, DeviceStatus status, Instant occurredAt, Long wardId) {}
