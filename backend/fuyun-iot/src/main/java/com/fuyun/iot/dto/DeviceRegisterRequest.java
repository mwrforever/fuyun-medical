package com.fuyun.iot.dto;

import com.fuyun.iot.enums.DeviceAccessMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备注册请求（POST /api/v1/iot/devices 请求体）：接入登记表单的注册中心投影 + 本地档案
 * 必填列（device_type/access_mode 为 V400 NOT NULL 列，注册即建档）。语义校验（重复注册/
 * 注册中心可达性）归服务层。
 *
 * @param deviceId   设备标识（IoTDA 自然键，≤64 字符同 device_id 列宽），非空；来源：接入登记分配
 * @param nodeId     设备侧节点标识（物模型节点，≤64 字符），可空
 * @param productId  所属注册中心产品标识（≤64 字符），非空；来源：iot_product.product_id
 * @param deviceName 设备名称（≤128 字符同 device_name 列宽），非空
 * @param deviceType 设备类型（总 Spec 5.1 矩阵 15 类，≤32 字符），非空
 * @param accessMode 接入模式：A 直连/B 串口服务器/C 边缘适配器/D HL7 引擎，非空
 */
public record DeviceRegisterRequest(
        @NotBlank(message = "deviceId 不能为空") @Size(max = 64, message = "deviceId 最长 64 字符")
        String deviceId,

        @Size(max = 64, message = "nodeId 最长 64 字符") String nodeId,

        @NotBlank(message = "productId 不能为空") @Size(max = 64, message = "productId 最长 64 字符")
        String productId,

        @NotBlank(message = "deviceName 不能为空") @Size(max = 128, message = "deviceName 最长 128 字符")
        String deviceName,

        @NotBlank(message = "deviceType 不能为空") @Size(max = 32, message = "deviceType 最长 32 字符")
        String deviceType,

        @NotNull(message = "accessMode 不能为空") DeviceAccessMode accessMode) {}
