package com.fuyun.iot.properties;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 遥测五步校验配置属性（fuyun.iot.telemetry.* 前缀，P2 PR-2 Task 6 / FU-M14-05 管道深化）：
 * 时间合理性阈值与波形白名单通道参数，由 TelemetryIngestServiceImpl 消费。
 *
 * <p>record 构造器绑定 + JSR-303 绑定期校验（IotProperties/IotdaAdminProperties 同款形态，
 * 宪法 A.2-2/A.2-4）；默认值经 {@code @DefaultValue} 承载，application.yml 段与环境变量仅作
 * 覆盖入口。注册路径：fuyun-app IotConfig {@code @EnableConfigurationProperties}（与
 * IotProperties 同一注册点，单一来源防双注册冲突）。
 *
 * @param clockSkewThreshold         时间合理性阈值（步骤四）：遥测 occurred_at 与服务器当前时刻
 *                                   偏差超过该值标 SUSPECT 不丢弃（FU-M14-05 数据质量口径：设备
 *                                   时钟漂移是数据质量线索而非丢弃理由）；默认 300s；来源：
 *                                   fuyun.iot.telemetry.clock-skew-threshold
 * @param waveformWhitelistWardIds   波形白名单病区清单（通道准入面）：仅清单内病区（绑定快照
 *                                   ward_id）的波形类指标获准落库，清单外（含无绑定/未编病区）
 *                                   波形行丢弃并告警计数；空清单 = 全部拒绝（安全默认：未显式
 *                                   授权不收波形，防高体量波形数据灌库）；来源：
 *                                   fuyun.iot.telemetry.waveform-whitelist-ward-ids
 * @param waveformDailyQuota         波形日配额（条/天）：波形通道独立配额上限（14-iot FU-M14-05
 *                                   "独立配额，超配额自动降级并告警"）。预留参数，暂未接线——
 *                                   强制执行随 P1 波形查询通道（/telemetry/waveform）一并交付
 */
// TODO(waveform-quota): 波形日配额强制执行（超配额自动降级并告警），计划于 P1 波形白名单查询通道版本引入
@Validated
@ConfigurationProperties(prefix = "fuyun.iot.telemetry")
public record TelemetryValidationProperties(
        @DurationMin(nanos = 1, message = "clock-skew-threshold 必须为正时长") @DefaultValue("300s")
        Duration clockSkewThreshold,

        @DefaultValue List<Long> waveformWhitelistWardIds,

        @Min(value = 1, message = "waveform-daily-quota 必须为正整数") @DefaultValue("1000000")
        int waveformDailyQuota) {}
