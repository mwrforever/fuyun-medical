package com.fuyun.inpatient.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 住院域通用参数（Task 10 起 env 注入兜底，NursingProperties 同款 record 构造器绑定形态；
 * Bean 注册归 InpatientWebConfig @EnableConfigurationProperties——NursingWebConfig 先例；
 * application.yml fuyun.inpatient 段经 FUYUN_INPATIENT_* 环境变量注入，fuyun.billing 同款先例）。
 * 单位与语义：金额一律分（GC18 零金额输入红线不受影响——本参数为系统侧阈值非请求面字段）。
 *
 * @param depositFloorFen            押金下限阈值（分），默认 0——billing.deposit.changed 消费面
 *                                   的欠费判定基准：变动后余额低于本阈值即置
 *                                   inpatient_visit.arrears_flag=true（护士站欠费标识数据源），
 *                                   回升至阈值及以上复位 false；M04 本地阈值全局一份（担保
 *                                   白名单免提醒归 P3 注记），默认 0 即余额为负才计欠费
 * @param followUpIntervalDays       出院随访缺省时距（天），默认 14——离院确认随访计划生成面：
 *                                   请求未显式携带 followUpDays 时 plan_date=出院日后 N 日取
 *                                   本值（Task 9 常量缺省 7 日回接参数化，行为变更随 Task 10 留痕）
 * @param defaultExecuteWindowMinutes 临时/嘱托医嘱单次执行计划默认准备窗口（分钟），默认 60——
 *                                   单次计划 plan_time=生成时点+本窗口（即刻执行的备药准备
 *                                   缓冲；Task 7 常量 15 分钟回接参数化，行为变更随 Task 10 留痕）
 */
@ConfigurationProperties(prefix = "fuyun.inpatient")
public record InpatientProperties(
        @DefaultValue("0") long depositFloorFen,
        @DefaultValue("14") int followUpIntervalDays,
        @DefaultValue("60") int defaultExecuteWindowMinutes) {}
