package com.fuyun.iot.dto;

import com.fuyun.iot.enums.BindingStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 绑定记录分页查询请求（GET /api/v1/iot/bindings 查询参数绑定载体，page 0 基）。
 * 过滤条件全部可空 = 不过滤；page/size 缺省值（0/20）由服务层补齐（GET 幂等查询无强制必填面）。
 *
 * @param page     页码（0 基，≥0），可空缺省 0
 * @param size     单页条数（1-200），可空缺省 20
 * @param deviceId 设备标识精确过滤，可空
 * @param wardId   病区 id 精确过滤，可空
 * @param status   绑定状态过滤（BOUND/UNBINDING/UNBOUND），可空
 */
public record BindingQueryRequest(
        @Min(value = 0, message = "page 不能为负") Integer page,

        @Min(value = 1, message = "size 最小为 1") @Max(value = 200, message = "size 最大为 200")
        Integer size,

        String deviceId,
        Long wardId,
        BindingStatus status) {}
