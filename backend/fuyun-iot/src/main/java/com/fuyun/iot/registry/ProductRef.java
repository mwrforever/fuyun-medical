package com.fuyun.iot.registry;

/**
 * 产品引用载体（IotDeviceRegistry.createProduct 出参，record 不可变）：注册中心分配的产品
 * 标识回执，调用方以之落本地镜像行（iot_product.product_id 自然键）。
 *
 * @param productId 注册中心产品标识，非空；来源：注册中心创建响应（华为 IoTDA productId /
 *                  模拟实现 SIM- 前缀生成），禁止本地生成
 */
public record ProductRef(String productId) {}
