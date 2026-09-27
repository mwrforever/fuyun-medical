package com.fuyun.ward.dto;

import com.fuyun.ward.enums.CallStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 呼叫分页查询请求（GET /api/v1/ward/ward-calls 查询参数绑定载体，page 0 基）。
 * 过滤条件可空 = 不过滤；page/size 缺省值（0/20）由服务层补齐（AlarmQueryRequest 同款形态）。
 *
 * @param page   页码（0 基，≥0），可空缺省 0
 * @param size   单页条数（1-200），可空缺省 20
 * @param wardId 病区 ID 过滤，可空
 * @param status 呼叫状态过滤，可空
 */
public record WardCallQueryRequest(
        @Min(value = 0, message = "page 不能为负") Integer page,

        @Min(value = 1, message = "size 最小为 1") @Max(value = 200, message = "size 最大为 200")
        Integer size,

        Long wardId,
        CallStatus status) {}
