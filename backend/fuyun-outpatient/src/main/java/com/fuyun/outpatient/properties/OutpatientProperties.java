package com.fuyun.outpatient.properties;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 门诊域通用参数（fuyun.outpatient.* 前缀，backend 宪法 A.2-2/A.2-4；BillingRefundProperties
 * 同款 record 构造器绑定 + 启动期校验）：Task 3 落最小骨架（延迟档位 TTL 与预约参数缺省），
 * Task 5/6 逐字段消费。Bean 注册归 OutpatientMessagingConfig @EnableConfigurationProperties。
 *
 * @param appointmentTimeout    预约支付时限，默认 15m（fy.delay 档位 appointment-timeout 的 TTL
 *                              与 pay-hold 占位键 TTL 同源取值）；来源：fuyun.outpatient.appointment-timeout
 * @param onlineCancelBeforeDays 线上退号提前天数下限，默认 1（就诊日前不足此天数线上退号关闭，
 *                              转窗口办理，OP-1010 判定依据）；来源：fuyun.outpatient.online-cancel-before-days
 * @param noShowWindowDays      爽约统计窗口天数，默认 90（回溯窗口内累计爽约计数）
 * @param noShowThreshold       爽约限约阈值，默认 3（窗口内累计达阈值进入限约期）
 * @param restrictDays          爽约限约期天数，默认 90（达阈值后的禁预约时长）
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.outpatient")
public record OutpatientProperties(
        @DurationMin(minutes = 1, message = "appointmentTimeout 不得低于 1 分钟") @DefaultValue("15m")
        Duration appointmentTimeout,

        @Min(value = 0, message = "onlineCancelBeforeDays 不得为负") @DefaultValue("1")
        int onlineCancelBeforeDays,

        @Min(value = 1, message = "noShowWindowDays 必须为正") @DefaultValue("90")
        int noShowWindowDays,

        @Min(value = 1, message = "noShowThreshold 必须为正") @DefaultValue("3")
        int noShowThreshold,

        @Min(value = 1, message = "restrictDays 必须为正") @DefaultValue("90")
        int restrictDays) {}
