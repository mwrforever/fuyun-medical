package com.fuyun.nursing.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 护理域通用参数（env 注入兜底，BillingProperties 同款 record 构造器绑定形态；Bean 注册归
 * NursingWebConfig @EnableConfigurationProperties——BillingWebConfig 先例）。
 *
 * @param taskOverdueMinutes 护理任务逾期判定阈值（分钟），默认 30——查询侧惰性判定基准：
 *                           plan_time 早于「当前时间 − 该阈值」的在途任务计逾期并单次递增
 *                           升级计数（Spec :127 动作式逾期 + §12-3；发布 nursing.task.overdue
 *                           归 P2 延迟队列，本参数 P2 复用为延迟投递时距）
 */
@ConfigurationProperties(prefix = "fuyun.nursing")
public record NursingProperties(@DefaultValue("30") int taskOverdueMinutes) {}
