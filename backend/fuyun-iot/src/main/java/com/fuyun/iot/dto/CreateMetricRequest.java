package com.fuyun.iot.dto;

import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.MetricDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * MDC 字典登记请求（POST /api/v1/iot/metrics 请求体）：模块自管专业字典登记（不经 M01）。
 * metric_code 重复即 IOT-1005 唯一键冲突（服务层前置拒绝）。
 *
 * @param metricCode   MDC 编码（如 MDC_ECG_HEART_RATE），非空（≤64 字符）；来源：IEEE 11073 术语集
 * @param metricName   指标名称（中文），非空
 * @param category     指标类别，非空
 * @param dataType     数据类型，非空
 * @param unit         计量单位，可空
 * @param physioMin    生理极限下界，可空（无固定量纲，与指标同单位）
 * @param physioMax    生理极限上界，可空
 * @param defaultLevel 默认告警级别 INFO/WARNING/CRITICAL（与 iot_alarm_rule.level 同词表），可空
 */
public record CreateMetricRequest(
        @NotBlank(message = "metricCode 不能为空") @Size(max = 64, message = "metricCode 最长 64 字符")
        String metricCode,

        @NotBlank(message = "metricName 不能为空") @Size(max = 128, message = "metricName 最长 128 字符")
        String metricName,

        @NotNull(message = "category 不能为空") MetricCategory category,
        @NotNull(message = "dataType 不能为空") MetricDataType dataType,
        @Size(max = 32, message = "unit 最长 32 字符") String unit,
        BigDecimal physioMin,
        BigDecimal physioMax,
        @Size(max = 16, message = "defaultLevel 最长 16 字符") String defaultLevel) {}
