package com.fuyun.iot.registry;

import java.util.Map;

/**
 * 设备影子载体（IotDeviceRegistry.shadow 出参，record 不可变）：期望态与上报态双面属性快照。
 * 两面合并自注册中心各服务的影子数据（desired/reported），键为属性名、值为属性值原文。
 *
 * @param desired  期望面（管理台/云端下发待设备确认的属性集），非空（无数据为空 map）
 * @param reported 上报面（设备实际上报的属性集），非空（无数据为空 map）
 */
public record DeviceShadow(Map<String, Object> desired, Map<String, Object> reported) {}
