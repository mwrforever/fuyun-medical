package com.fuyun.iot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * 数据质量统计查询请求（GET /api/v1/iot/quality/stats 与 /device-usage 查询参数绑定载体，
 * page 0 基；FU-M14-11）。
 *
 * <p>statDate 缺省 = 当日（UTC 日切）；deviceId 缺省 = 不过滤仅列表（惰性重算只对显式指定的
 * 单设备触发，避免无指定查询触发全院重算风暴）。
 *
 * @param page     页码（0 基，≥0），可空缺省 0
 * @param size     单页条数（1-200），可空缺省 20
 * @param deviceId 设备标识过滤，可空（显式指定时触发该设备当日统计惰性重算）
 * @param statDate 统计归属自然日过滤，可空缺省当日
 */
public record QualityStatQueryRequest(
        @Min(value = 0, message = "page 不能为负") Integer page,

        @Min(value = 1, message = "size 最小为 1") @Max(value = 200, message = "size 最大为 200")
        Integer size,

        String deviceId,

        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "statDate 须为 ISO 日期（yyyy-MM-dd）")
        LocalDate statDate) {}
