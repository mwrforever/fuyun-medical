package com.fuyun.iot.service;

import com.fuyun.iot.api.DeviceStatusEvent;

/**
 * 设备状态服务：IoTDA 设备状态帧驱动的 iot_device 状态机执行点（M14 Spec §5，BRIEF-PR4-01 §1.5）。
 *
 * <p>调用方为 AMQP 消费者（internal，B4.2 任务 B）：状态帧解析成功后即时处理（不入攒批）；
 * 返回值供发布器决定是否发布 iot.device.status-changed（V403 已登记）。
 */
public interface IDeviceStatusService {

    /**
     * 应用一次设备状态变更：按 deviceId 查档案，存在则条件更新 status 并按状态分派时间字段
     * （ONLINE→last_online_at、OFFLINE→last_offline_at、其余状态不更新时间字段），不存在则
     * info 日志跳过（P0 无设备注册 API，档案由数据种子提供）。
     *
     * @param event 设备状态变更事件，非空；来源：AMQP 状态帧解析产物（status 已校验值域，
     *              occurredAt 已定型 UTC）；wardId 可空（P0 契约不含）
     * @return true=设备有效且状态已更新（调用方据此发布状态事件）；false=设备不存在或
     *         条件更新未命中（并发删除/竞态），不发布事件
     */
    boolean apply(DeviceStatusEvent event);
}
