package com.fuyun.iot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 产品上架请求（POST /api/v1/iot/products 请求体）：管理台上架表单的注册中心投影。物模型
 * JSON 可空（产品可先上架后经 model-sync 补齐）；语义校验（注册中心可达性）归服务层。
 *
 * @param productName         产品名称，非空（≤128 字符，V1007 product_name 列宽）；来源：上架表单
 * @param deviceType          设备类型（总 Spec 5.1 矩阵 15 类），非空
 * @param protocolType        协议类型（MQTT/LwM2M/HTTPS/Modbus 等），非空
 * @param dataFormat          数据格式（JSON/二进制），非空
 * @param manufacturerName    厂商名称，可空
 * @param industry            所属行业，可空
 * @param description         产品描述，可空（≤255 字符）
 * @param modelDefinitionJson 物模型 JSON（服务能力数组形态），可空；来源：产品定义
 */
public record CreateProductRequest(
        @NotBlank(message = "productName 不能为空") @Size(max = 128, message = "productName 最长 128 字符")
        String productName,

        @NotBlank(message = "deviceType 不能为空") @Size(max = 32, message = "deviceType 最长 32 字符")
        String deviceType,

        @NotBlank(message = "protocolType 不能为空") @Size(max = 32, message = "protocolType 最长 32 字符")
        String protocolType,

        @NotBlank(message = "dataFormat 不能为空") @Size(max = 16, message = "dataFormat 最长 16 字符")
        String dataFormat,

        @Size(max = 128, message = "manufacturerName 最长 128 字符")
        String manufacturerName,

        @Size(max = 64, message = "industry 最长 64 字符") String industry,
        @Size(max = 255, message = "description 最长 255 字符") String description,
        String modelDefinitionJson) {}
