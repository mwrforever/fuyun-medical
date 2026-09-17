package com.fuyun.patient.constants;

import java.util.Set;

/**
 * 患者域消息治理常量（M02 Spec §7 八事件 + fy.topic 治理约定）：事件类型字面量与 V105 种子行、
 * api 包 payload record 三方一致，任何一侧变更属契约变更（M-25 成对语义受双向评审约束）。
 */
public final class PatientMessagingConstants {

    /** 模块域标识：队列命名 q.<consumerModule>.<eventType> 与信封 producer 字段共用 */
    public static final String MODULE = "patient";

    /** 领域事件主交换机（治理构件固定三件套之一，禁私建） */
    public static final String TOPIC_EXCHANGE = "fy.topic";

    /** 事件类型：患者建档（含未实名标记） */
    public static final String EVENT_CREATED = "patient.patient.created";

    /** 事件类型：患者主数据变更 */
    public static final String EVENT_UPDATED = "patient.patient.updated";

    /** 事件类型：合并完成（与 patient.patient.split 成对，M-25） */
    public static final String EVENT_MERGED = "patient.patient.merged";

    /** 事件类型：拆分恢复（与 patient.patient.merged 成对，M-25） */
    public static final String EVENT_SPLIT = "patient.patient.split";

    /** 事件类型：冻结（与 patient.patient.unfrozen 成对，M-25） */
    public static final String EVENT_FROZEN = "patient.patient.frozen";

    /** 事件类型：解冻（与 patient.patient.frozen 成对，M-25） */
    public static final String EVENT_UNFROZEN = "patient.patient.unfrozen";

    /** 事件类型：标识变更（解析缓存失效依据） */
    public static final String EVENT_IDENTIFIER_CHANGED = "patient.identifier.changed";

    /** 事件类型：健康档案变更（过敏项摘要） */
    public static final String EVENT_HEALTH_SUMMARY_UPDATED = "patient.health-summary.updated";

    /**
     * 本模块自消费（缓存失效）的事件全集：created 为新增档无既有缓存不消费；
     * merged/split 成对、frozen/unfrozen 成对声明（M-25 对本模块自订阅同样适用）。
     */
    public static final Set<String> CACHE_EVICTION_EVENT_TYPES =
            Set.of(EVENT_UPDATED, EVENT_MERGED, EVENT_SPLIT, EVENT_FROZEN, EVENT_UNFROZEN, EVENT_IDENTIFIER_CHANGED);

    /** 私有构造器：常量类禁止实例化（backend 宪法 A.2-6） */
    private PatientMessagingConstants() {}
}
