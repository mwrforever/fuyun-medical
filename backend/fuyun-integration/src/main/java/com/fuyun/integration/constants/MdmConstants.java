package com.fuyun.integration.constants;

import java.util.Map;
import java.util.Set;

/**
 * 主数据分发治理常量（M20 Spec §4 mdm_subscription/mdm_dispatch_log 词表与 FU-M20-04 口径）。
 *
 * <p>主题取值与 Spec 原文字段说明一一对应（`topic(dict/org/user/param/practice)`）；同步方式取
 * 事件订阅/接口拉取两值（`sync_mode(事件订阅/接口拉取)`）。写路径的取值校验以此为准（禁字符串散落）。
 *
 * <p>演进点（不在本 PR）：对账状态在 PENDING 之外还需 CONSISTENT/LAGGING 等取值，随「每日版本对账 +
 * 落后自动全量重发」任务（依赖 M01 版本化回源接口）一并引入——本 PR 不预置未使用常量（死代码零容忍）。
 */
public final class MdmConstants {

    /** 主题：字典（M01 dict） */
    public static final String TOPIC_DICT = "dict";

    /** 主题：组织机构（M01 org） */
    public static final String TOPIC_ORG = "org";

    /** 主题：人员（M01 user） */
    public static final String TOPIC_USER = "user";

    /** 主题：系统参数（M01 param） */
    public static final String TOPIC_PARAM = "param";

    /** 主题：执业授权（M01 practice） */
    public static final String TOPIC_PRACTICE = "practice";

    /** 合法主题全集：登记入参校验与查询过滤的唯一依据 */
    public static final Set<String> TOPICS = Set.of(TOPIC_DICT, TOPIC_ORG, TOPIC_USER, TOPIC_PARAM, TOPIC_PRACTICE);

    /** 同步方式：事件订阅（fy.topic 广播链路，本模块记分发流水） */
    public static final String SYNC_MODE_EVENT_SUBSCRIBE = "EVENT_SUBSCRIBE";

    /** 同步方式：接口拉取（订阅方经 M01 回源接口按版本拉取） */
    public static final String SYNC_MODE_API_PULL = "API_PULL";

    /** 对账状态：待对账（登记初值；对账任务引入后追加其余取值） */
    public static final String RECON_STATUS_PENDING = "PENDING";

    /** 主数据事件类型（M01 发布清单，M20 §7 订阅清单逐条对应；V5 种子已登记，订阅方经声明构件自动登记） */
    public static final String EVENT_DICT_PUBLISHED = "system.dict.published";

    /** 主数据事件类型：机构变更 */
    public static final String EVENT_ORG_CHANGED = "system.org.changed";

    /** 主数据事件类型：用户变更 */
    public static final String EVENT_USER_CHANGED = "system.user.changed";

    /** 主数据事件类型：参数变更 */
    public static final String EVENT_PARAM_CHANGED = "system.param.changed";

    /** 主数据事件类型：执业授权变更 */
    public static final String EVENT_PRACTICE_CHANGED = "system.practice.changed";

    /** 事件类型 → 主数据主题映射：分发流水登记的 topic 推导源（未登记事件不经本链路消费） */
    public static final Map<String, String> TOPIC_BY_EVENT_TYPE = Map.of(
            EVENT_DICT_PUBLISHED, TOPIC_DICT,
            EVENT_ORG_CHANGED, TOPIC_ORG,
            EVENT_USER_CHANGED, TOPIC_USER,
            EVENT_PARAM_CHANGED, TOPIC_PARAM,
            EVENT_PRACTICE_CHANGED, TOPIC_PRACTICE);

    /** 分发模式：广播（M01 变更事件经 fy.topic 广播；FULL_REDISPATCH 待 M01 回源接口就绪后引入） */
    public static final String DISPATCH_MODE_BROADCAST = "BROADCAST";

    /**
     * 私有构造器：常量类禁止实例化（backend 宪法 A.2-6）。
     */
    private MdmConstants() {}
}
