package com.fuyun.integration.constants;

/**
 * 消息治理常量：交换机/队列命名、队列参数键与治理状态值的集中定义（M20 治理约定词表）。
 *
 * <p>状态值一律字符串常量（README §3"状态字段用 VARCHAR 常量"），本 PR 不落任何枚举类，
 * 规避 TASK.md D-6（enum/ 保留字目录未裁决，BRIEF-PR2-01 §8-1）。
 * 队列参数键经 QueueBuilder 写入时与框架内部同键，常量保留为契约词表与测试断言锚点。
 */
public final class MessagingConstants {

    /** 领域事件主交换机：全部业务事件经此路由（Topic 类型） */
    public static final String EXCHANGE_TOPIC = "fy.topic";

    /** 死信交换机：消费重试耗尽与不合规信封死信统一汇入（Topic 类型） */
    public static final String EXCHANGE_DLX = "fy.dlx";

    /** 延迟交换机：延迟队列到期经 DLX 参数回投 fy.topic（Topic 类型） */
    public static final String EXCHANGE_DELAY = "fy.delay";

    /** 死信统一队列：全系统唯一死信落点，dead_letter 台账的监听源 */
    public static final String QUEUE_DEAD_LETTER = "q.integration.dead-letter";

    /** 消费队列命名前缀：完整队列名 = q.&lt;消费者模块&gt;.&lt;事件类型&gt; */
    public static final String QUEUE_PREFIX = "q.";

    /** 延迟队列命名前缀：完整队列名 = delay.&lt;业务名&gt;，一条队列一个延迟档位（A.5-7） */
    public static final String DELAY_QUEUE_PREFIX = "delay.";

    /** 队列参数：死信转发目标交换机 */
    public static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";

    /** 队列参数：消息在队列内的存活时长（毫秒），延迟档位载体 */
    public static final String X_MESSAGE_TTL = "x-message-ttl";

    /** 队列参数：死信转发目标路由键（延迟队列到期回投 fy.topic 的目标事件） */
    public static final String X_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    /** 队列参数：队列类型（业务队列一律 quorum，总 Spec D1） */
    public static final String X_QUEUE_TYPE = "x-queue-type";

    /** 队列类型取值：quorum（复制多数派确认） */
    public static final String QUEUE_TYPE_QUORUM = "quorum";

    /** 绑定键通配：死信交换机全量路由（仅死信统一队列使用） */
    public static final String BINDING_KEY_ALL = "#";

    /** 消息头：x-death 死信轨迹（RabbitMQ 服务端注入，死信溯源数据源） */
    public static final String HEADER_X_DEATH = "x-death";

    /** 幂等 Redis 前置键前缀（A.5-1 冒号分层），完整键 = 本前缀 + 消费者模块 + ":" + eventId */
    public static final String IDEMPOTENCY_KEY_PREFIX = "fy:integration:idempotency:";

    /** event_registry 状态：生效 */
    public static final String REGISTRY_STATUS_ACTIVE = "ACTIVE";

    /** event_registry 状态：废止（废止事件禁止新订阅） */
    public static final String REGISTRY_STATUS_DEPRECATED = "DEPRECATED";

    /** received_event 状态：已消费（P0 唯一写入值；B2.2 幂等构件启用） */
    public static final String RECEIVED_STATUS_PROCESSED = "PROCESSED";

    /** dead_letter 状态：待处理（P0 死信落库初始值；B2.2 启用） */
    public static final String DEAD_LETTER_STATUS_PENDING = "PENDING";

    /** dead_letter 状态：已重放（重放投递成功，M20 §5 状态机 PENDING → REPLAYED） */
    public static final String DEAD_LETTER_STATUS_REPLAYED = "REPLAYED";

    /** dead_letter 状态：已关闭（终态，必须填写关闭原因；不得再重放） */
    public static final String DEAD_LETTER_STATUS_CLOSED = "CLOSED";

    /** received_event 状态：消费失败（D-7 回查必须过滤 status=PROCESSED，FAILED 行不得误判为已处理） */
    public static final String RECEIVED_STATUS_FAILED = "FAILED";

    /** event_publication 完成态派生值：completion_date 非空 = 投递完成（框架已标记） */
    public static final String PUBLICATION_STATUS_COMPLETED = "COMPLETED";

    /** event_publication 完成态派生值：completion_date 为空 = 未完成（监听器失败/实例宕机，待 EventOpsJob 重投） */
    public static final String PUBLICATION_STATUS_INCOMPLETE = "INCOMPLETE";

    /** 死信重推上限（次）：达到后重推接口拒绝（Spec 未定义，控制器 2026-09-15 拍板口径） */
    public static final int DEAD_LETTER_REPLAY_MAX_COUNT = 3;

    /** fail_reason 列宽防线：dead_letter.fail_reason 与 received_event.fail_reason 同为 VARCHAR(1000) */
    public static final int FAIL_REASON_MAX_LENGTH = 1000;

    /** source_queue 列宽防线：dead_letter.source_queue VARCHAR(128)（x-death 队列名理论可超） */
    public static final int DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH = 128;

    /** routing_key 列宽防线：dead_letter.routing_key VARCHAR(128) */
    public static final int DEAD_LETTER_ROUTING_KEY_MAX_LENGTH = 128;

    /** event_id 列宽防线：dead_letter.event_id VARCHAR(64)（信封 eventId 为 UUID 字符串，理论超长面） */
    public static final int DEAD_LETTER_EVENT_ID_MAX_LENGTH = 64;

    /** event_type 列宽防线：dead_letter.event_type VARCHAR(128) */
    public static final int DEAD_LETTER_EVENT_TYPE_MAX_LENGTH = 128;

    /** 载荷预览长度（字符）：列表页仅出头部预览，全文只经详情端点（诊断看载荷） */
    public static final int DEAD_LETTER_PAYLOAD_PREVIEW_LENGTH = 200;

    /** handler 列宽防线：dead_letter.handler VARCHAR(64)（操作人标识超长收口，防整行写入失败） */
    public static final int HANDLER_MAX_LENGTH = 64;

    /** target_modules 列宽防线：mdm_dispatch_log.target_modules VARCHAR(1000) */
    public static final int MDM_TARGET_MODULES_MAX_LENGTH = 1000;

    /** 本模块消费者/生产者域标识：队列命名与 received_event.consumer_module 的第二要素 */
    public static final String MODULE = "integration";

    /** subscriber_modules 广播标记：broadcast=true 时订阅清单记为此值（零订阅广播，R6-13） */
    public static final String SUBSCRIBER_BROADCAST = "broadcast";

    /** 信封载荷契约默认版本（CF-1 冻结；登记种子与后续契约行引用） */
    public static final String ENVELOPE_DEFAULT_VERSION = "1";

    /**
     * 私有构造器：常量类禁止实例化（backend 宪法 A.2-6）。
     */
    private MessagingConstants() {}
}
