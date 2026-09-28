package com.fuyun.iot.properties;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 命令下发配置属性（fuyun.iot.command.* 前缀，P2 PR-2 Task 8 / FU-M14-09）：同步等待超时与
 * 治疗级豁免开关。
 *
 * <p>record 构造器绑定 + JSR-303 绑定期校验（AlarmProperties 同款形态，宪法 A.2-2/A.2-4）；
 * 默认值经 {@code @DefaultValue} 承载。注册路径：fuyun-app IotConfig
 * {@code @EnableConfigurationProperties}（与 IotProperties 同一注册点，单一来源防双注册冲突）。
 *
 * @param syncTimeout      同步命令等待回执超时：超时置 TIMEOUT 终态并 warn 告警；默认 30s；来源：
 *                         fuyun.iot.command.sync-timeout
 * @param treatmentAllowed 治疗级命令豁免开关：默认 false（白名单红线——治疗级默认禁放行，
 *                         IOT-1014）；显式置 true 方放行，且每次使用 warn 告警并以
 *                         iot_command_log 行留痕（safety_level=TREATMENT 行 + M01 审计切面
 *                         双留痕；升级审批面缺位注记 GC17，归 Task 18）；来源：
 *                         fuyun.iot.command.treatment-allowed
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.iot.command")
public record CommandProperties(
        @DurationMin(nanos = 1, message = "sync-timeout 必须为正时长") @DefaultValue("30s")
        Duration syncTimeout,

        @DefaultValue("false") boolean treatmentAllowed) {}
