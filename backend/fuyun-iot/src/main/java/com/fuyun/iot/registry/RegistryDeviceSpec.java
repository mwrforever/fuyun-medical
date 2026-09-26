package com.fuyun.iot.registry;

/**
 * 设备注册规格载体（IotDeviceRegistry.registerDevice 入参，record 不可变）：设备接入登记的
 * 注册中心投影。deviceId 为 IoTDA 设备标识自然键（V400 主键偏离申报同源）。
 *
 * @param deviceId   设备标识（自然键，由接入登记分配），非空
 * @param nodeId     设备侧节点标识（物模型节点），可空
 * @param productId  所属注册中心产品标识，非空；来源：iot_product.product_id
 * @param deviceName 设备名称，非空；来源：设备档案
 */
public record RegistryDeviceSpec(String deviceId, String nodeId, String productId, String deviceName) {}
