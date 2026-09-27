package com.fuyun.iot.dto;

import com.fuyun.iot.enums.GatewayMode;
import com.fuyun.iot.enums.GatewayStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 网关保存请求（POST/PUT /api/v1/iot/gateways 请求体，登记与更新共用一形）：热备对端合法性
 * （存在/不自引用/不成环）由服务层校验拒保存（IOT-1025，standby 链完整性语义）。
 *
 * @param gatewayId    网关标识（IoTDA 网关设备标识自然键），非空（≤64 字符）；来源：管理台表单
 *                     （经 IoTDA 注册的网关设备标识）。更新路径以 URL 路径参数定位，本字段仅契约
 *                     回显（不一致以路径为准）
 * @param gatewayName  网关名称，非空（≤128 字符）；来源：管理台表单
 * @param mode         接入模式（B 串口服务器/C 边缘适配器），非空；来源：管理台表单
 * @param standbyOf    热备对端网关标识，可空（无双机热备场景）；须指向存在网关且禁自引用/成环
 * @param wardId       归属病区 ID，非空；来源：管理台表单
 * @param status       网关状态，非空（ONLINE 在线/OFFLINE 离线/MAINTENANCE 维护）；来源：管理台表单
 */
public record SaveGatewayRequest(
        @NotBlank(message = "gatewayId 不能为空") @Size(max = 64, message = "gatewayId 最长 64 字符")
        String gatewayId,

        @NotBlank(message = "gatewayName 不能为空") @Size(max = 128, message = "gatewayName 最长 128 字符")
        String gatewayName,

        @NotNull(message = "mode 不能为空") GatewayMode mode,

        @Size(max = 64, message = "standbyOf 最长 64 字符") String standbyOf,

        @NotNull(message = "wardId 不能为空") Long wardId,

        @NotNull(message = "status 不能为空") GatewayStatus status) {}
