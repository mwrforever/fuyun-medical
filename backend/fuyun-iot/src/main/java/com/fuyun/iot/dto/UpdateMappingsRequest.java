package com.fuyun.iot.dto;

import com.fuyun.iot.enums.MismatchStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 属性 MDC 映射编辑请求（PUT /api/v1/iot/products/{id}/metric-mappings 请求体）：全量替换
 * 该产品物模型属性映射（逻辑删旧行 + 落新行）。mismatchStrategy 缺省 RAW_PASSTHROUGH
 * （失配原文透传红线，服务层回填）。
 *
 * @param mappings 映射清单，非空且至少 1 条；来源：管理台映射编辑表单
 */
public record UpdateMappingsRequest(
        @NotEmpty(message = "mappings 不能为空") @Valid List<MappingItem> mappings) {

    /**
     * 单条属性映射。
     *
     * @param propertyName     物模型属性名，非空；同批重复即 IOT-1005 唯一键冲突（服务层前置拒绝）
     * @param metricCode       MDC 编码，非空；字典无命中即 IOT-1004（服务层校验）
     * @param mismatchStrategy 失配策略，可空（缺省 RAW_PASSTHROUGH）
     */
    public record MappingItem(
            @NotBlank(message = "propertyName 不能为空") @Size(max = 128, message = "propertyName 最长 128 字符")
            String propertyName,

            @NotBlank(message = "metricCode 不能为空") @Size(max = 64, message = "metricCode 最长 64 字符")
            String metricCode,

            MismatchStrategy mismatchStrategy) {}
}
