package com.fuyun.nursing.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 护理域通用参数（env 注入兜底，BillingProperties 同款 record 构造器绑定形态；Bean 注册归
 * NursingWebConfig @EnableConfigurationProperties——BillingWebConfig 先例）。
 *
 * @param taskOverdueMinutes 护理任务逾期判定阈值（分钟），默认 30——查询侧惰性判定基准：
 *                           plan_time 早于「当前时间 − 该阈值」的在途任务计逾期并单次递增
 *                           升级计数（Spec :127 动作式逾期 + §12-3）；发布 nursing.task.overdue
 *                           归 P2 延迟队列，本参数 P2 复用为延迟投递时距
 * @param taskOverdue        任务逾期动作式升级参数组（P2 PR-3 Task 9 tick 驱动面）：查询侧
 *                           惰性判定沿用旧键 taskOverdueMinutes 不迁移，tick 扫描链路改由本
 *                           参数组承载（首逾提醒/升级链/自续期心跳开关）
 */
@ConfigurationProperties(prefix = "fuyun.nursing")
public record NursingProperties(
        @DefaultValue("30") int taskOverdueMinutes,
        @DefaultValue TaskOverdue taskOverdue) {

    /**
     * 任务逾期 tick 参数组（delay.task-overdue 60 秒档位到期回投的消费侧判定基准）：
     * remindAfterMinutes 首逾提醒阈值（越过即置 overdue_flag 并发布 id 61 事件，escalationCount=1
     * 责任护士档）；escalateAfterMinutes 升级链开启阈值（elapsed 越过才开始档位比对）；
     * escalateIntervalMinutes 升级步进（目标档 = floor(elapsed/步进) 封顶 2——1 责任护士、
     * 2 护士长）；tickSelfRearm 自续期心跳开关（false=消费后不再续投下一条 tick，链路静止）。
     *
     * @param remindAfterMinutes      首逾提醒阈值（分钟），默认 30；来源：env 注入
     * @param escalateAfterMinutes    升级链开启阈值（分钟），默认 60；来源：env 注入
     * @param escalateIntervalMinutes 升级步进（分钟），默认 30；来源：env 注入
     * @param tickSelfRearm           tick 自续期开关，默认 true（进程存活即有心跳）；来源：env 注入
     */
    public record TaskOverdue(
            @DefaultValue("30") int remindAfterMinutes,
            @DefaultValue("60") int escalateAfterMinutes,
            @DefaultValue("30") int escalateIntervalMinutes,
            @DefaultValue("true") boolean tickSelfRearm) {}
}
