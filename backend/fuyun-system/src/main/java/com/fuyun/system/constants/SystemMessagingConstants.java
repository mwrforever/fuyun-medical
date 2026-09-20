package com.fuyun.system.constants;

/**
 * 系统模块消息治理常量（M20 治理约定在本模块的取值词表）：事件类型、消费队列命名与
 * 主交换机名的集中定义（backend 宪法 A.2-6 禁魔法值散落）。
 *
 * <p>队列/交换机命名与 fuyun-integration 治理构件（QueueGovernorImpl）规则同源推导：
 * 消费队列 = q.&lt;消费者模块&gt;.&lt;事件类型&gt;；本模块仅依赖 fuyun-integration api 包，
 * 交换机/队列名字面量在此固化（与治理约定一致性由集成测试把关）。
 */
public final class SystemMessagingConstants {

    /** 主交换机：领域事件统一路由目标（与 M20 治理约定 fy.topic 一致，禁私建交换机 A.5-4） */
    public static final String TOPIC_EXCHANGE = "fy.topic";

    /** 字典发布广播事件类型：{@code <模块>.<实体>.<动作>}（V5 种子 id=2 已登记，M01 Spec §7） */
    public static final String EVENT_DICT_PUBLISHED = "system.dict.published";

    /** 执业授权变更事件类型：grant 登记/withdraw 停权广播（V5 种子 id=6 既有登记，零新增） */
    public static final String EVENT_PRACTICE_CHANGED = "system.practice.changed";

    /** 本模块域标识：发布方（producer）与消费方（consumer module）同源 */
    public static final String MODULE = "system";

    /** 字典发布消费队列：q.system.system.dict.published（构件命名规则同源推导） */
    public static final String QUEUE_DICT_PUBLISHED = "q.system.system.dict.published";

    /** 纯常量类，禁止实例化（backend 宪法 A.2-6） */
    private SystemMessagingConstants() {}
}
