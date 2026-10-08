package com.fuyun.ops.constants;

import java.time.Duration;

/**
 * M19 运营域工作台切片常量（public final static + 私有构造器，宪法 A.2-6）：快照缓存键、
 * TTL、事件流有界上限与趋势窗口天数等运行期不变量集中承载，禁魔法值散落业务代码。
 * 线程安全：不可变常量类，无并发风险。
 */
public final class OpsConstants {

    /**
     * 工作台 overview 快照缓存键（A.5-1 fy:{module}:{biz}:{id} 冒号分层；suffix=聚合维度
     * overview 单值）。
     */
    public static final String OVERVIEW_SNAPSHOT_KEY = "fy:ops:snapshot:workbench:overview";

    /**
     * overview 快照缓存 TTL：5 秒（立项计划册 2 冻结值；NurseBoardServiceImpl GC13 同款口径
     * ——工作台轮询频度下读路径不触库；A.5-1 禁无 TTL 键红线）。
     */
    public static final Duration OVERVIEW_SNAPSHOT_TTL = Duration.ofSeconds(5);

    /** 14 日趋势窗口天数（立项计划册 2 冻结：含今日在内近 14 个自然日） */
    public static final int TREND_DAYS = 14;

    /** 事件流合并清单总上限（大屏/工作台有界纪律——NurseBoardServiceImpl 同款截断口径） */
    public static final int EVENTS_LIMIT = 50;

    /** 事件流类型词表：待支付费用（billing HTTP 轮询派生源） */
    public static final String EVENT_TYPE_FEE_PENDING = "FEE_PENDING";

    /** 事件流类型词表：待配药（pharmacy HTTP 轮询派生源） */
    public static final String EVENT_TYPE_DISPENSE_PENDING = "DISPENSE_PENDING";

    /** 事件流类型词表：危急值（M07 检验域缺位——类型占位随 M07 落地回填，勿删词表项） */
    public static final String EVENT_TYPE_CRITICAL_VALUE = "CRITICAL_VALUE";

    private OpsConstants() {}
}
