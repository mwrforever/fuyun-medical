package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.MetricDataType;
import java.math.BigDecimal;

/**
 * MDC 术语字典视图（GET/POST /api/v1/iot/metrics 出参）：跨品牌术语归一基准面。
 *
 * @param metricCode   MDC 编码，非空
 * @param metricName   指标名称，非空
 * @param category     指标类别，非空
 * @param dataType     数据类型，非空
 * @param unit         计量单位，可空
 * @param physioMin    生理极限下界，可空
 * @param physioMax    生理极限上界，可空
 * @param defaultLevel 默认告警级别 INFO/WARNING/CRITICAL，可空
 */
public record MetricDictVO(
        String metricCode,
        String metricName,
        MetricCategory category,
        MetricDataType dataType,
        String unit,
        BigDecimal physioMin,
        BigDecimal physioMax,
        String defaultLevel) {

    /**
     * 实体 → 视图工厂映射。
     *
     * @param entity 字典实体，非空
     * @return 字典视图，非空
     */
    public static MetricDictVO from(IotMetricDictEntity entity) {
        return new MetricDictVO(
                entity.getMetricCode(),
                entity.getMetricName(),
                entity.getCategory(),
                entity.getDataType(),
                entity.getUnit(),
                entity.getPhysioMin(),
                entity.getPhysioMax(),
                entity.getDefaultLevel());
    }
}
