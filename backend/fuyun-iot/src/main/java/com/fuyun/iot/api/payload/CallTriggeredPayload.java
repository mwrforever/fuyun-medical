package com.fuyun.iot.api.payload;

import java.time.Instant;

/**
 * 设备呼叫触发事件载荷（iot.call.triggered，V1004 id 81 冻结契约）：病区呼叫设备触发呼叫时发布，
 * 为 M16 呼叫域入口事件（护士站呼叫应答链路由此驱动）。
 *
 * @param callNo      呼叫业务号，非空；来源：呼叫域签发
 * @param deviceId    IoTDA 设备标识，非空；来源：触发呼叫的呼叫分机/床头设备
 * @param callType    呼叫类型，非空（紧急/普通等呼叫分类，值域随呼叫域冻结）；来源：设备上报呼叫帧
 * @param bedId       床位 ID，非空；来源：设备绑定档案（呼叫按床位定位患者）
 * @param wardId      病区 ID，非空；来源：设备绑定档案（呼叫按病区路由值班台）
 * @param triggeredAt 触发时刻（UTC），非空；来源：设备呼叫帧 occurredAt
 */
public record CallTriggeredPayload(
        String callNo, String deviceId, String callType, Long bedId, Long wardId, Instant triggeredAt) {}
