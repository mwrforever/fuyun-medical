package com.fuyun.nursing.service;

/**
 * 任务逾期扫描升级服务（P2 PR-3 Task 9 tick 三段式的段①②承载，M05 FU-M05-07）：
 * delay.task-overdue 60 秒档位到期回投 nursing.task-overdue.tick 触发扫表判定与动作式
 * 升级——查询侧惰性判定（读时单次递增）之外的主动扫描面，发布 nursing.task.overdue
 * （V800 id 61）。消费编排（自续期段③与回签补偿挂接）归 TaskOverdueTickListener，
 * 本服务只承载扫描与升级动作（事务边界见实现侧）。
 *
 * <p>线程安全：无状态 singleton；动作批次经编程式事务独立承载（实现侧）。
 */
public interface ITaskOverdueService {

    /**
     * 逾期扫描与升级动作（tick 消费体段①②，扫表判定在事务外——避免长事务扫表）：
     * ①在途未标记且 plan_time+remindAfterMinutes &lt; now 的行 casMarkOverdue 置位（首次递增
     * escalationCount=1 责任护士档）并事务内发布 nursing.task.overdue；②在途已标记且
     * plan_time+escalateAfterMinutes &lt; now 的行按目标档 floor(elapsed/escalateIntervalMinutes)
     * 封顶 2 比对，未达目标档经 casEscalateOverdue 期望值 CAS 递增（护士长档=2）再发布同事件。
     * 空扫描零命中零副作用（空 tick 幂等忽略——自续期心跳归监听器承载）。
     *
     * @return 本轮动作总数（首逾标记数 + 升级递增数；观测口径，零=空扫描）
     */
    int scanAndEscalate();
}
