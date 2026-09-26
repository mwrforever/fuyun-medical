package com.fuyun.iot.properties;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 告警引擎配置属性（fuyun.iot.alarm.* 前缀，P2 PR-2 Task 7 / FU-M14-08）：风暴抑制④的窗口与
 * 基线参数、最新值快照 TTL（brief 冻结默认：10 分钟窗口 / 基线 50 / TTL ≥2×采集周期取 10 分钟）。
 *
 * <p>record 构造器绑定 + JSR-303 绑定期校验（TelemetryValidationProperties 同款形态，宪法
 * A.2-2/A.2-4）；默认值经 {@code @DefaultValue} 承载。注册路径：fuyun-app IotConfig
 * {@code @EnableConfigurationProperties}（与 IotProperties 同一注册点，单一来源防双注册冲突）。
 *
 * @param stormWindow       风暴窗口（抑制④单位时间）：窗口内触发量超基线即置风暴标记（键
 *                          fy:iot:alarm:storm:{ruleId}，TTL=本窗口）；默认 10 分钟；来源：
 *                          fuyun.iot.alarm.storm-window
 * @param stormBaseline     风暴基线（抑制④触发量阈值）：窗口内同规则触发计数超过该值判风暴态；
 *                          默认 50；来源：fuyun.iot.alarm.storm-baseline
 * @param latestSnapshotTtl 最新值快照 TTL（阈值评估辅助面 fy:iot:snapshot:latest:{deviceId}:
 *                          {metricCode}）：须 ≥2×采集周期，默认 10 分钟；来源：
 *                          fuyun.iot.alarm.latest-snapshot-ttl
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.iot.alarm")
public record AlarmProperties(
        @DurationMin(nanos = 1, message = "storm-window 必须为正时长") @DefaultValue("10m")
        Duration stormWindow,

        @Min(value = 1, message = "storm-baseline 必须为正整数") @DefaultValue("50")
        int stormBaseline,

        @DurationMin(nanos = 1, message = "latest-snapshot-ttl 必须为正时长") @DefaultValue("10m")
        Duration latestSnapshotTtl) {}
