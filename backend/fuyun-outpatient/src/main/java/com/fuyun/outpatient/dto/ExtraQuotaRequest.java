package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 加号请求（POST /api/v1/outpatient/number-pools/{id}/extra-quota，M03 FU-M03-01）：池行
 * total_quota 增量授权，单次数量上限 50（服务端与契约双层校验，超限 OP-1019）。
 *
 * @param count 加号数量，1~50；来源：医生/管理端加号表单
 */
public record ExtraQuotaRequest(
        @NotNull(message = "count 不得为空")
        @Min(value = 1, message = "count 至少为 1")
        @Max(value = 50, message = "count 单次最多 50")
        Integer count) {}
