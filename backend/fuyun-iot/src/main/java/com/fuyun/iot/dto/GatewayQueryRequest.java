package com.fuyun.iot.dto;

import com.fuyun.iot.enums.GatewayMode;
import com.fuyun.iot.enums.GatewayStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 网关分页查询请求（GET /api/v1/iot/gateways 查询参数载体，page 0 基）。过滤条件全部可空 =
 * 不过滤；page/size 缺省值（0/20）由服务层补齐（GET 幂等查询无强制必填面，DeviceQueryRequest
 * 同款形态）。
 *
 * @param page   页码（0 基，≥0），可空缺省 0；来源：管理台分页控件
 * @param size   单页条数（1-200），可空缺省 20
 * @param wardId 病区 id 精确过滤，可空；来源：管理台病区维度过滤
 * @param mode   接入模式过滤（B/C），可空
 * @param status 网关状态过滤（ONLINE/OFFLINE/MAINTENANCE），可空
 */
public record GatewayQueryRequest(
        @Min(value = 0, message = "page 不能为负") Integer page,

        @Min(value = 1, message = "size 最小为 1") @Max(value = 200, message = "size 最大为 200")
        Integer size,

        Long wardId,
        GatewayMode mode,
        GatewayStatus status,

        @Size(max = 64, message = "gatewayId 最长 64 字符") String gatewayId) {}
