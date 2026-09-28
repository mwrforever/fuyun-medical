package com.fuyun.iot.registry;

/**
 * 产品规格载体（IotDeviceRegistry.createProduct 入参，record 不可变）：管理台上架表单的注册
 * 中心投影——字段与 IoTDA 产品模型对齐，物模型 JSON 随产品创建一并提交（IoTDA 无独立模型
 * 上传端点，3.1.218 SDK 实测；未定义模型可空，由 model-sync 补齐）。
 *
 * @param productName         产品名称，非空；来源：管理台上架表单
 * @param deviceType          设备类型（总 Spec 5.1 矩阵 15 类），非空
 * @param protocolType        协议类型（MQTT/LwM2M/HTTPS/Modbus 等），非空
 * @param dataFormat          数据格式（JSON/二进制），非空
 * @param manufacturerName    厂商名称，可空；来源：产品档案
 * @param industry            所属行业，可空
 * @param description         产品描述，可空
 * @param modelDefinitionJson 物模型 JSON（服务能力数组形态，ServiceCapability 同构），可空；
 *                            来源：产品定义，model-sync 时以本地快照为权威
 */
public record ProductSpec(
        String productName,
        String deviceType,
        String protocolType,
        String dataFormat,
        String manufacturerName,
        String industry,
        String description,
        String modelDefinitionJson) {}
