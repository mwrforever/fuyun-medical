package com.fuyun.iot.dto;

import com.fuyun.iot.enums.BindType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备绑定请求（POST /api/v1/iot/bindings 请求体）：绑定快照五元组 + 绑定模式与原因。
 * visitId 为 CF-3 定长 14 位字符串承载（V1006 类型改造后形态）；语义校验（设备状态/生效绑定
 * 冲突/患者冻结/在途就诊）归服务层校验链，本载体只做结构性约束。
 *
 * @param deviceId   IoTDA 设备标识，非空；来源：护士站/病区管理端选择
 * @param patientId  患者主索引，非空（服务层经 PatientContextResolver 归一主档后落行）；来源：选患者
 * @param visitId    住院就诊号（CF-3，≤14 位字符串），非空；来源：在院就诊列表
 * @param bedId      床位 id，可空（移动式绑定不落床位）
 * @param wardId     病区 id，非空（播报路由与病区设备墙归属依据）
 * @param bindType   绑定模式 FIXED/MOBILE，非空
 * @param bindReason 绑定原因，可空（≤255 字符，列宽 V400 bind_reason VARCHAR(255)）
 */
public record BindDeviceRequest(
        @NotBlank(message = "deviceId 不能为空") @Size(max = 64, message = "deviceId 最长 64 字符")
        String deviceId,

        @NotNull(message = "patientId 不能为空") Long patientId,

        @NotBlank(message = "visitId 不能为空") @Size(max = 14, message = "visitId 最长 14 字符")
        String visitId,

        Long bedId,
        @NotNull(message = "wardId 不能为空") Long wardId,
        @NotNull(message = "bindType 不能为空") BindType bindType,
        @Size(max = 255, message = "bindReason 最长 255 字符") String bindReason) {}
