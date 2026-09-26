package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.enums.MismatchStrategy;

/**
 * 属性 MDC 映射视图（PUT /api/v1/iot/products/{id}/metric-mappings 出参元素）：物模型属性
 * 归一映射面。
 *
 * @param id               映射行雪花 id，非空（替换重建后为新 id）
 * @param productId        注册中心产品标识，非空
 * @param propertyName     物模型属性名，非空
 * @param metricCode       MDC 编码，非空
 * @param mismatchStrategy 失配策略（缺省 RAW_PASSTHROUGH），非空
 */
public record MetricMappingVO(
        Long id, String productId, String propertyName, String metricCode, MismatchStrategy mismatchStrategy) {

    /**
     * 实体 → 视图工厂映射。
     *
     * @param entity 映射实体，非空
     * @return 映射视图，非空
     */
    public static MetricMappingVO from(IotMetricMappingEntity entity) {
        return new MetricMappingVO(
                entity.getId(),
                entity.getProductId(),
                entity.getPropertyName(),
                entity.getMetricCode(),
                entity.getMismatchStrategy());
    }
}
