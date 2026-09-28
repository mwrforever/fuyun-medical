package com.fuyun.iot.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 遥测数据质量日统计视图（GET /api/v1/iot/quality/stats 与 /device-usage 出参，FU-M14-11）。
 *
 * <p>usageRate 设备利用率口径（FU-M15-05 数据源）：有数据时长/全天，按
 * received_count / expected_count 折算（期望为 0 记 0——未登记标称频率设备不参与利用率统计）。
 *
 * @param deviceId      IoTDA 设备标识，非空
 * @param statDate      统计归属自然日，非空
 * @param expectedCount 期望采样数（标称频率×在线分钟数推算），非空
 * @param receivedCount 实际接收采样数，非空
 * @param missingRate   缺数率 0~1，非空
 * @param anomalyCount  异常值数（quality != GOOD 行数），非空
 * @param qualityScore  质量得分 0~100，非空
 * @param usageRate     设备利用率 0~1（received/expected 折算，期望为 0 记 0），非空
 */
public record DataQualityStatVO(
        String deviceId,
        LocalDate statDate,
        Long expectedCount,
        Long receivedCount,
        BigDecimal missingRate,
        Long anomalyCount,
        BigDecimal qualityScore,
        BigDecimal usageRate) {

    /**
     * 统计实体 → 出网视图（唯一转换出口；利用率按 received/expected 现算，期望为 0 记 0）。
     *
     * @param entity 统计实体，非空
     * @return 统计视图，非空
     */
    public static DataQualityStatVO from(com.fuyun.iot.entity.IotDataQualityStatEntity entity) {
        long expected = entity.getExpectedCount() == null ? 0 : entity.getExpectedCount();
        long received = entity.getReceivedCount() == null ? 0 : entity.getReceivedCount();
        BigDecimal usage = expected > 0
                ? BigDecimal.valueOf(received).divide(BigDecimal.valueOf(expected), 4, java.math.RoundingMode.DOWN)
                : BigDecimal.ZERO;
        // 利用率上限截断为 1（设备超预期频次上报时利用率不超 100%，语义为有数据时长占比）
        if (usage.compareTo(BigDecimal.ONE) > 0) {
            usage = BigDecimal.ONE;
        }
        return new DataQualityStatVO(
                entity.getDeviceId(),
                entity.getStatDate(),
                entity.getExpectedCount(),
                entity.getReceivedCount(),
                entity.getMissingRate(),
                entity.getAnomalyCount(),
                entity.getQualityScore(),
                usage);
    }
}
