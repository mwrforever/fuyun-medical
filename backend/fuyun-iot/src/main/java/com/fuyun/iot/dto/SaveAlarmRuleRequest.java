package com.fuyun.iot.dto;

import com.fuyun.iot.enums.AlarmLevel;
import com.fuyun.iot.enums.AlarmRuleType;
import com.fuyun.iot.enums.ThresholdOp;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 告警规则保存请求（POST/PUT /api/v1/iot/alarm-rules 请求体，登记与更新共用一形）：
 * 规则型参数按类型的条件必填由服务层校验拒保存（抖动防护②——THRESHOLD 缺持续时长/恢复带、
 * OFFLINE 缺离线判定秒即 IOT-1013 409）。
 *
 * @param ruleName           规则名称，非空（≤128 字符）；来源：管理台表单
 * @param ruleType           规则类型，非空；来源：管理台表单
 * @param deviceId           适用设备号，可空（NULL=全部设备）；来源：管理台表单
 * @param metricCode         指标编码（THRESHOLD/DEVICE_ALARM 必填，服务层校验），可空
 * @param compareOp          阈值比较方向 &gt;/&lt;（THRESHOLD 必填，服务层校验），可空
 * @param thresholdValue     阈值（THRESHOLD 必填，服务层校验），可空
 * @param durationSecs       持续时长秒（THRESHOLD 必填，服务层校验），可空
 * @param recoveryBand       恢复带（THRESHOLD 必填，服务层校验），可空
 * @param silenceWindowSecs  静默窗口秒，可空缺省 300（服务层补齐）
 * @param offlineSecs        离线判定秒（OFFLINE 必填，服务层校验），可空
 * @param alarmLevel         告警级别，非空；来源：管理台表单
 * @param escalateAfterSecs  升级时限秒，可空缺省 300（服务层补齐）
 * @param enabled            是否启用，可空缺省 true（服务层补齐）
 */
public record SaveAlarmRuleRequest(
        @NotBlank(message = "ruleName 不能为空") @Size(max = 128, message = "ruleName 最长 128 字符")
        String ruleName,

        @NotNull(message = "ruleType 不能为空") AlarmRuleType ruleType,

        @Size(max = 64, message = "deviceId 最长 64 字符") String deviceId,

        @Size(max = 64, message = "metricCode 最长 64 字符") String metricCode,

        ThresholdOp compareOp,

        BigDecimal thresholdValue,

        Integer durationSecs,

        BigDecimal recoveryBand,

        Integer silenceWindowSecs,

        Integer offlineSecs,

        @NotNull(message = "alarmLevel 不能为空") AlarmLevel alarmLevel,

        Integer escalateAfterSecs,

        Boolean enabled) {}
