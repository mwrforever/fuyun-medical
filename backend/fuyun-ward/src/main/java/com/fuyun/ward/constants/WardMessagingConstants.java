package com.fuyun.ward.constants;

import java.time.Duration;

/**
 * M16 智慧病房消息与域常量：事件/队列命名、输液档阈值与指标编码、升级时限、Redis 键前缀的集中定义
 * （M16 词表，禁魔法值散落——backend 宪法 A.2-6）。
 *
 * <p>fy.topic 事件词表与 fuyun-integration MessagingConstants 命名口径一致（q. 前缀队列、
 * ward.cold-chain.alert-archived 事件，V1102 已登记 event_registry id 82，与 ward/api 载荷 record
 * 组件名三方一致，契约锚 WardMessagingContractTest）；订阅事件（iot/nursing 前缀）均为登记在册
 * 事件（V1004/V800 种子）。本模块自持一份常量避免跨模块常量耦合（B.2-2 只依赖 api 契约，常量
 * 词表非 api 契约）。
 */
public final class WardMessagingConstants {

    /** 发布/消费模块域标识：event_registry producer、幂等分域 consumer_module 与队列命名首段共用 */
    public static final String MODULE = "ward";

    /** 领域事件主交换机：全部业务事件经此路由（Topic 类型，与 M20 治理词表同源） */
    public static final String TOPIC_EXCHANGE = "fy.topic";

    /** 消费队列命名前缀（q.&lt;消费者模块&gt;.&lt;事件类型&gt;，与治理构件 declareConsumerQueue 同源推导） */
    public static final String QUEUE_PREFIX = "q." + MODULE + ".";

    /** 发布事件：冷链告警处置归档（V1102 id 82；M05 督办联动消费；载荷 ColdChainAlertArchivedPayload） */
    public static final String EVENT_COLD_CHAIN_ALERT_ARCHIVED = "ward.cold-chain.alert-archived";

    /** 订阅事件：告警触发（V1004 id 74 登记；ward 侧输液告急落呼叫行消费源——q.ward.iot.alarm.triggered） */
    public static final String EVENT_IOT_ALARM_TRIGGERED = "iot.alarm.triggered";

    /** 订阅事件：遥测断流异常（V1004 id 78 登记；ward 侧体征采集质量注记消费源——q.ward.iot.telemetry.anomaly） */
    public static final String EVENT_IOT_TELEMETRY_ANOMALY = "iot.telemetry.anomaly";

    /** 订阅事件：拔针/输注结束（V800 id 63 登记；ward 侧输液呼叫复位消费源——q.ward.nursing.infusion.completed） */
    public static final String EVENT_NURSING_INFUSION_COMPLETED = "nursing.infusion.completed";

    /**
     * 订阅事件：设备呼叫触发（V1004 id 81 登记；M16 呼叫域入口——设备源呼叫落行消费源，
     * q.ward.iot.call.triggered；Task 12 审查 Important-1 回接：iot 侧 CALL_TRANSFER 联动动作
     * 经本事件扇出至 ward 呼叫域，Task 9 联调债自此闭合）。
     */
    public static final String EVENT_IOT_CALL_TRIGGERED = "iot.call.triggered";

    /** 本模块消费队列：告警触发（q.ward.iot.alarm.triggered，输液告急落呼叫行——每消费者一队列先例，与 q.iot.iot.alarm.triggered 分立互不竞争） */
    public static final String QUEUE_IOT_ALARM_TRIGGERED = QUEUE_PREFIX + EVENT_IOT_ALARM_TRIGGERED;

    /** 本模块消费队列：遥测断流异常（q.ward.iot.telemetry.anomaly，体征采集质量注记） */
    public static final String QUEUE_IOT_TELEMETRY_ANOMALY = QUEUE_PREFIX + EVENT_IOT_TELEMETRY_ANOMALY;

    /** 本模块消费队列：拔针/输注结束（q.ward.nursing.infusion.completed，输液呼叫复位——PR-3 nursing 发布后生效） */
    public static final String QUEUE_NURSING_INFUSION_COMPLETED = QUEUE_PREFIX + EVENT_NURSING_INFUSION_COMPLETED;

    /** 本模块消费队列：设备呼叫触发（q.ward.iot.call.triggered，设备源呼叫落行——M16-01 呼叫域入口） */
    public static final String QUEUE_IOT_CALL_TRIGGERED = QUEUE_PREFIX + EVENT_IOT_CALL_TRIGGERED;

    /** 发号键段与单号前缀：呼叫 CALL（CALL{yyyyMMdd}{%05d}） */
    public static final String SEQ_TYPE_CALL = "CALL";

    /** 发号键段与单号前缀：冷链档案 ARCH（ARCH{yyyyMMdd}{%05d}——档案/记录号 brief 未冻结前缀，照 CALL 同款形态扩展申报） */
    public static final String SEQ_TYPE_ARCHIVE = "ARCH";

    /** 发号键段与单号前缀：冷链记录 CCR（CCR{yyyyMMdd}{%05d}，同上申报） */
    public static final String SEQ_TYPE_RECORD = "CCR";

    /** 发号 Redis 键前缀：fy:ward:seq:（A.5-1 命名，ward 域隔离——照 fy:iot:seq: 形态） */
    public static final String SEQ_KEY_PREFIX = "fy:ward:seq:";

    /** 呼叫升级默认时限秒（brief 冻结默认 300s；ward_call 无规则列，常量承载——读时惰性判定锚 created_at） */
    public static final int ESCALATE_AFTER_SECS_DEFAULT = 300;

    /**
     * 输液告急指标编码（ward 侧消费判定锚）。<b>词表缺位申报</b>：iot metric 字典种子（V1007）
     * 无输液余量指标词目，本值取 iot 侧联动预置模板/测试夹具同源的告警类型词形（任务冻结面
     * 「alarm_type=INFUSION_SHORTAGE」在 AlarmTriggeredPayload 无独立 alarm_type 字段，以
     * metricCode 承载——ward 以 metricCode 精确相等判定输液告急帧），真实联调以 IoTDA 物模型/
     * metric 字典登记值对齐修订。
     */
    public static final String INFUSION_SHORTAGE_METRIC_CODE = "INFUSION_SHORTAGE";

    /** 输液滴速指标编码（看板余量/滴速聚合第二指标，词表缺位申报同上） */
    public static final String INFUSION_DROP_RATE_METRIC_CODE = "MDC_INFUSION_DROP_RATE";

    /**
     * 床垫在床/离床 presence 指标编码（体征视图 presence 类指标锚）。<b>词表缺位申报</b>：iot
     * metric 字典无 presence 词目，本编码照 MDC 词形占位，联调对齐修订。
     */
    public static final String PRESENCE_METRIC_CODE = "MDC_BED_PRESENCE";

    /** 输液余量告警黄色档阈值（ml）：≤15 提示黄档（brief 冻结 15ml 黄/10ml 橙/5ml 红三档映射） */
    public static final int INFUSION_YELLOW_THRESHOLD_ML = 15;

    /** 输液余量告警橙色档阈值（ml）：≤10 橙档（黄档之上叠加判定，RED 优先） */
    public static final int INFUSION_ORANGE_THRESHOLD_ML = 10;

    /** 输液余量告警红色档阈值（ml）：≤5 红档（最高优先级，同时触发 ward 系统级呼叫落行） */
    public static final int INFUSION_RED_THRESHOLD_ML = 5;

    /** 体征采集质量注记 Redis 键前缀：fy:ward:vital:anomaly:（A.5-1 命名，拼 deviceId；TTL 24h 自然过期） */
    public static final String VITAL_ANOMALY_KEY_PREFIX = "fy:ward:vital:anomaly:";

    /** 体征采集质量注记 Redis 值 TTL：24 小时（断流异常为瞬态提示，过期自然清零免清理） */
    public static final Duration VITAL_ANOMALY_TTL = Duration.ofHours(24);

    /** MDC traceId 键名：与 fuyun.trace.mdc-key 配置默认值一致（发布点从 MDC 取当前值进信封） */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 私有构造器：常量类禁止实例化（backend 宪法 A.2-6）。
     */
    private WardMessagingConstants() {}
}
