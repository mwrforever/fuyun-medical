package com.fuyun.iot.dto;

import com.fuyun.iot.enums.DeviceStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 设备分页查询请求（GET /api/v1/iot/devices 查询参数载体，page 0 基）。过滤条件全部可空 =
 * 不过滤；page/size 缺省值（0/20）由服务层补齐（GET 幂等查询无强制必填面）。
 *
 * @param page      页码（0 基，≥0），可空缺省 0；来源：管理台分页控件
 * @param size      单页条数（1-200），可空缺省 20
 * @param wardId    病区 id 精确过滤，可空；来源：病区设备墙
 * @param status    设备状态过滤（INACTIVE/ONLINE/OFFLINE/ABNORMAL/DISABLED），可空
 * @param productId 产品标识精确过滤，可空
 */
public record DeviceQueryRequest(
        @Min(value = 0, message = "page 不能为负") Integer page,

        @Min(value = 1, message = "size 最小为 1") @Max(value = 200, message = "size 最大为 200")
        Integer size,

        Long wardId,
        DeviceStatus status,
        String productId) {}
