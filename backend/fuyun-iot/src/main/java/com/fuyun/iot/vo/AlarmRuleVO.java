package com.fuyun.iot.vo;

import com.fuyun.iot.entity.IotAlarmRuleEntity;
import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 告警规则视图对象（告警规则域出网载体）：规则全字段出网。实体禁直出（宪法 B.1 出网边界），
 * 查询/登记/更新响应统一经 {@link #from} 静态工厂转换。
 *
 * @param id                规则行雪花 id，非空（落库后回填）
 * @param ruleName          规则名称，非空
 * @param ruleType          规则类型：DEVICE_ALARM/THRESHOLD/OFFLINE，非空
 * @param deviceId          适用设备号（NULL=全部设备），可空
 * @param metricCode        指标编码，可空（OFFLINE 规则为空）
 * @param compareOp         阈值比较方向 &gt;/&lt;，可空
 * @param thresholdValue    阈值，可空
 * @param durationSecs      持续时长秒，可空
 * @param recoveryBand      恢复带，可空
 * @param silenceWindowSecs 静默窗口秒，非空（默认 300）
 * @param offlineSecs       离线判定秒，可空
 * @param alarmLevel        告警级别，非空
 * @param escalateAfterSecs 升级时限秒，非空（默认 300）
 * @param enabled           是否启用，非空
 * @param createdAt         创建时刻，可空（落库前为空）
 * @param updatedAt         更新时刻，可空（数据库触发器维护）
 */
public record AlarmRuleVO(
        Long id,
        String ruleName,
        AlarmRuleType ruleType,
        String deviceId,
        String metricCode,
        ThresholdOp compareOp,
        BigDecimal thresholdValue,
        Integer durationSecs,
        BigDecimal recoveryBand,
        Integer silenceWindowSecs,
        Integer offlineSecs,
        AlarmLevel alarmLevel,
        Integer escalateAfterSecs,
        Boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * 实体 → 出网视图（唯一转换出口，字段一一对应浅拷贝）。
     *
     * @param entity 规则实体，非空；来源：mapper 查询或落库组装
     * @return 规则视图，非空
     */
    public static AlarmRuleVO from(IotAlarmRuleEntity entity) {
        return new AlarmRuleVO(
                entity.getId(),
                entity.getRuleName(),
                entity.getRuleType(),
                entity.getDeviceId(),
                entity.getMetricCode(),
                entity.getCompareOp(),
                entity.getThresholdValue(),
                entity.getDurationSecs(),
                entity.getRecoveryBand(),
                entity.getSilenceWindowSecs(),
                entity.getOfflineSecs(),
                entity.getAlarmLevel(),
                entity.getEscalateAfterSecs(),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
