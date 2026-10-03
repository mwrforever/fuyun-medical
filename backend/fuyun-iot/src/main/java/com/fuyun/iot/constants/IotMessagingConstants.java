package com.fuyun.iot.constants;

import java.util.Set;

/**
 * IoT 消息与遥测管道常量：事件/队列/交换机命名、遥测与状态帧判别键、IoTDA AMQP 推送报文字段键、
 * 错误留痕截断上限的集中定义（M14 词表，禁魔法值散落——backend 宪法 A.2-6）。
 *
 * <p>fy.topic 事件三件套词表与 fuyun-integration MessagingConstants 命名口径一致（q. 前缀队列、
 * iot.device.status-changed 事件，V403 已登记 event_registry；CF-7 实装事件族八条随 V1004 登记
 * id 74–81，与 iot/api/payload 载荷 record 组件名三方一致，契约锚 IotMessagingContractTest）；
 * 本模块自持一份常量避免跨模块常量耦合（B.2-2 只依赖 api 契约，常量词表非 api 契约）。P2 PR-3
 * Task 6 起跨域订阅 nursing 输液起止两事件（V800 id 62/63 登记，iot 侧自持消费常量，载荷经
 * EventEnvelope JSON 取冻结子集——不依赖 nursing jar）。帧判别键为
 * CF-7 线格式（BRIEF-PR4-01
 * §1.3 P0 线格式契约）与状态帧 P0 契约形态的字段名；IOTDA_ 前缀常量为 IoTDA AMQP 推送报文
 * （TASK.md L-3 冻结映射）的字段键——解析器以顶层 {@link #FRAME_FIELD_RESOURCE} 精确等于
 * {@link #IOTDA_RESOURCE_DEVICE_PROPERTY} 判别该形态（三形态判别之 IoTDA 推送形态，优先于
 * CF-7 遥测/状态判别）。
 */
public final class IotMessagingConstants {

    /** 发布方模块标识：event_registry producer 与幂等分域 consumer_module 共用 */
    public static final String MODULE = "iot";

    /** 领域事件主交换机：全部业务事件经此路由（Topic 类型，与 M20 治理词表同源） */
    public static final String TOPIC_EXCHANGE = "fy.topic";

    /** 消费队列命名前缀（q.&lt;消费者模块&gt;.&lt;事件类型&gt;，与治理构件 declareConsumerQueue 同源推导） */
    public static final String QUEUE_PREFIX = "q." + MODULE + ".";

    /** 发布事件：设备状态变更（V403 id 8 已登记 event_registry，P0 占位载荷随 P1 冻结；CF-7 扩展事件族见下方 V1004 增量） */
    public static final String EVENT_DEVICE_STATUS = "iot.device.status-changed";

    /** 发布事件：告警触发（V1004 id 74；M05 挂单升级与 M16 播报消费；载荷 AlarmTriggeredPayload） */
    public static final String EVENT_ALARM_TRIGGERED = "iot.alarm.triggered";

    /** 发布事件：告警升级动作（V1004 id 75；升级为动作非状态；载荷 AlarmEscalatedPayload） */
    public static final String EVENT_ALARM_ESCALATED = "iot.alarm.escalated";

    /** 发布事件：告警关闭（V1004 id 76；M05/M16 复位与统计；载荷 AlarmClosedPayload） */
    public static final String EVENT_ALARM_CLOSED = "iot.alarm.closed";

    /** 发布事件：绑定变更（V1004 id 77；绑定五元组变更广播；载荷 BindingChangedPayload） */
    public static final String EVENT_BINDING_CHANGED = "iot.binding.changed";

    /** 发布事件：遥测断流异常（V1004 id 78；M16 体征质量确认提示；载荷 TelemetryAnomalyPayload） */
    public static final String EVENT_TELEMETRY_ANOMALY = "iot.telemetry.anomaly";

    /** 发布事件：命令结果回推（V1004 id 79；载荷 CommandCompletedPayload） */
    public static final String EVENT_COMMAND_COMPLETED = "iot.command.completed";

    /** 发布事件：联动执行（V1004 id 80；载荷 LinkageExecutedPayload） */
    public static final String EVENT_LINKAGE_EXECUTED = "iot.linkage.executed";

    /** 发布事件：设备呼叫触发（V1004 id 81；M16 呼叫域入口；载荷 CallTriggeredPayload） */
    public static final String EVENT_CALL_TRIGGERED = "iot.call.triggered";

    /** 消费事件：开始输注（V800 id 62，P2 PR-3 Task 6 订阅——输液监测关联建立；producer=nursing） */
    public static final String EVENT_SUB_NURSING_INFUSION_STARTED = "nursing.infusion.started";

    /** 消费事件：拔针/输注结束（V800 id 63，P2 PR-3 Task 6 订阅——患者维度监测停止；producer=nursing） */
    public static final String EVENT_SUB_NURSING_INFUSION_COMPLETED = "nursing.infusion.completed";

    /** 本模块自事件消费队列：q.&lt;消费者模块&gt;.&lt;事件类型&gt;（治理构件声明用） */
    public static final String QUEUE_DEVICE_STATUS = "q.iot.iot.device.status-changed";

    /** 跨域消费队列：护理开始输注（q.iot.nursing.infusion.started，P2 PR-3 Task 6——监测关联建立） */
    public static final String QUEUE_NURSING_INFUSION_STARTED = QUEUE_PREFIX + EVENT_SUB_NURSING_INFUSION_STARTED;

    /** 跨域消费队列：护理拔针/输注结束（q.iot.nursing.infusion.completed，P2 PR-3 Task 6——监测停止） */
    public static final String QUEUE_NURSING_INFUSION_COMPLETED = QUEUE_PREFIX + EVENT_SUB_NURSING_INFUSION_COMPLETED;

    /** 本模块自事件消费队列：告警触发（q.iot.iot.alarm.triggered，P2 PR-2 Task 9 联动触发源主入口） */
    public static final String QUEUE_ALARM_TRIGGERED = "q.iot.iot.alarm.triggered";

    /** 本模块自事件消费队列：告警关闭（q.iot.iot.alarm.closed，P2 PR-2 Task 11 扇出扩订阅——大屏摘要变更触发源） */
    public static final String QUEUE_ALARM_CLOSED = "q.iot.iot.alarm.closed";

    /**
     * 扇出消费者域标识（P2 PR-2 Task 11 R1 修复）：与 {@link #MODULE} 同为 iot 模块内的独立
     * 消费者域——同事件多消费者按「每消费者一队列」形态分队列（治理队列命名与幂等键第二要素
     * 均由本域派生），防联动链消费的 received_event PROCESSED 行（consumer_module=iot）经幂等
     * 回查抑制扇出链消费。
     */
    public static final String FANOUT_CONSUMER_MODULE = "iot-fanout";

    /** 本模块扇出消费队列：告警触发（q.iot-fanout.iot.alarm.triggered，P2 PR-2 Task 11 R1 修复——大屏摘要变更触发源独立队列） */
    public static final String QUEUE_ALARM_TRIGGERED_FANOUT = "q.iot-fanout.iot.alarm.triggered";

    /** 本地攒批消费链固定消费组标识（iot_consumer_stat.consumer_group 落值：AMQP 消费链单攒批器单组，
     * 真实 IoTDA 消费组名随联调对齐） */
    public static final String LOCAL_CONSUMER_GROUP = "iot-amqp";

    /** 遥测断流异常类型：STREAM_GAP 断流（在线但超标称周期 N 倍时长无数据，TelemetryAnomalyPayload
     * anomalyType 词表首项，质量异常分类扩充随后续任务顺延） */
    public static final String ANOMALY_TYPE_STREAM_GAP = "STREAM_GAP";

    /** MDC traceId 键名：与 fuyun.trace.mdc-key 配置默认值一致（发布点从 MDC 取当前值进信封，
     * AMQP 消费线程无 HTTP 上下文时取值为 null，信封契约允许） */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 帧判别键：设备号（遥测/状态帧公共必填字段） */
    public static final String FRAME_FIELD_DEVICE_ID = "deviceId";

    /** 帧判别键：指标编码（遥测形态判据之一，CF-7 字段名） */
    public static final String FRAME_FIELD_METRIC_CODE = "metricCode";

    /** 帧判别键：采集值（遥测形态判据之一，CF-7 字段名） */
    public static final String FRAME_FIELD_VALUE = "value";

    /** 帧判别键：计量单位（遥测帧可选字段，CF-7 字段名） */
    public static final String FRAME_FIELD_UNIT = "unit";

    /** 帧判别键：发生时刻（遥测/状态帧公共必填字段，ISO-8601 时间） */
    public static final String FRAME_FIELD_OCCURRED_AT = "occurredAt";

    /** 帧判别键：数据质量（遥测帧可选字段，值域 GOOD/SUSPECT/BAD，缺省 GOOD） */
    public static final String FRAME_FIELD_QUALITY = "quality";

    /** 帧判别键：接入来源（遥测帧可选字段，值域 IOTDA/HL7，缺省 IOTDA） */
    public static final String FRAME_FIELD_SOURCE = "source";

    /** 帧判别键：设备状态（状态形态判据之一，值域 = DeviceStatus 枚举） */
    public static final String FRAME_FIELD_STATUS = "status";

    /** IoTDA 推送形态判别键：顶层 resource 字段（三形态判别之 IoTDA 推送形态判据，TASK.md L-3） */
    public static final String FRAME_FIELD_RESOURCE = "resource";

    /** IoTDA 推送形态 resource 精确匹配值：设备属性上报（等于该值判为推送形态，其余回退 CF-7 判别） */
    public static final String IOTDA_RESOURCE_DEVICE_PROPERTY = "device.property";

    /**
     * IoTDA 推送形态 resource 精确匹配值：设备告警（第四形态判别，P2 PR-2 Task 7 透传规则源）。
     * <b>样例缺位申报</b>：仓库与模拟器无真实 device.alarm 报文样例，本形态按 IoTDA 官方文档
     * 《数据转发规则-设备告警》报文结构实现（resource=device.alarm + notify_data.header/body），
     * 真实联调如发现字段漂移以样例实测修订。
     */
    public static final String IOTDA_RESOURCE_DEVICE_ALARM = "device.alarm";

    /** IoTDA 设备告警报文字段：告警标识（body.alarm_id，告警名缺失时 metricCode 回退来源） */
    public static final String IOTDA_FIELD_ALARM_ID = "alarm_id";

    /** IoTDA 设备告警报文字段：告警名称（body.name，metricCode 唯一来源——物模型事件名） */
    public static final String IOTDA_FIELD_ALARM_NAME = "name";

    /** IoTDA 设备告警报文字段：告警级别（body.severity，IoTDA 词表透传注记） */
    public static final String IOTDA_FIELD_ALARM_SEVERITY = "severity";

    /** IoTDA 设备告警报文字段：告警描述（body.description，triggerValue 唯一来源） */
    public static final String IOTDA_FIELD_ALARM_DESCRIPTION = "description";

    /** IoTDA 推送报文字段：数据通知载体（header 设备标识 + body 服务属性列表） */
    public static final String IOTDA_FIELD_NOTIFY_DATA = "notify_data";

    /** IoTDA 推送报文字段：设备标识头（device_id/node_id/product_id 载体） */
    public static final String IOTDA_FIELD_HEADER = "header";

    /** IoTDA 推送报文字段：设备号（推送帧 deviceId 唯一来源，notify_data.header 下必填非空白） */
    public static final String IOTDA_FIELD_DEVICE_ID = "device_id";

    /** IoTDA 推送报文字段：事件时刻（顶层毫秒精度 ISO-8601，occurredAt 唯一来源） */
    public static final String IOTDA_FIELD_EVENT_TIME_MS = "event_time_ms";

    /** IoTDA 推送报文字段：数据体（services 服务属性列表载体） */
    public static final String IOTDA_FIELD_BODY = "body";

    /** IoTDA 推送报文字段：服务列表（每元素含 service_id 与 properties，至少 1 个服务） */
    public static final String IOTDA_FIELD_SERVICES = "services";

    /** IoTDA 推送报文字段：服务属性表（键=属性名即 metricCode，值=采集值；必须为非空对象） */
    public static final String IOTDA_FIELD_PROPERTIES = "properties";

    /**
     * IoTDA 推送形态 resource 精确匹配值：设备命令状态（第五形态判别，P2 PR-2 Task 8 结果回推
     * 消费源）。<b>样例缺位申报</b>：仓库与模拟器无真实命令结果帧样例，本形态按 IoTDA 官方文档
     * 报文结构实现（resource=device.command.status + notify_data.header/body；设备侧原始响应经
     * {@code $oc/devices/{device_id}/sys/commands/response/request_id={request_id}} 主题上行，
     * 经规则引擎「异步命令状态」数据源转发为本形态），真实联调如发现字段漂移以样例实测修订。
     */
    public static final String IOTDA_RESOURCE_DEVICE_COMMAND_STATUS = "device.command.status";

    /** IoTDA 命令状态报文字段：平台命令标识（body.command_id，结果归属对账锚，非空） */
    public static final String IOTDA_FIELD_COMMAND_ID = "command_id";

    /** IoTDA 命令状态报文字段：命令状态（body.status，值域见 {@link #IOTDA_COMMAND_STATUSES}，非空） */
    public static final String IOTDA_FIELD_COMMAND_STATUS = "status";

    /** IoTDA 命令状态报文字段：执行结果摘要（body.result，可空——失败原因/回执摘要承载） */
    public static final String IOTDA_FIELD_COMMAND_RESULT = "result";

    /**
     * IoTDA 命令状态值域（body.status 判别集合，样例缺位申报同上）：DELIVERED 已送达/SUCCESS
     * 成功/FAILED 失败/TIMEOUT 超时/EXPIRED 缓存过期/REMOVED 已撤销；值域外属毒丸（不得静默
     * 降级——错误归类将污染命令状态机）。
     */
    public static final Set<String> IOTDA_COMMAND_STATUSES =
            Set.of("DELIVERED", "SUCCESS", "FAILED", "TIMEOUT", "EXPIRED", "REMOVED");

    /** IoTDA 推送报文字段：服务标识（白名单脱敏保留字段，毒丸留痕溯源用） */
    public static final String IOTDA_FIELD_SERVICE_ID = "service_id";

    /** IoTDA 推送报文字段：上报事件类型（如 report；白名单脱敏保留字段，非判别键） */
    public static final String IOTDA_FIELD_EVENT = "event";

    /** IoTDA 推送报文字段：节点标识（设备物理标识；白名单脱敏保留字段） */
    public static final String IOTDA_FIELD_NODE_ID = "node_id";

    /** IoTDA 推送报文字段：产品标识（白名单脱敏保留字段） */
    public static final String IOTDA_FIELD_PRODUCT_ID = "product_id";

    /** STOMP 遥测摘要主题前缀：/topic/iot/telemetry/{wardId}（B4.3 推送语义，简报 §1.4） */
    public static final String TOPIC_TELEMETRY_PREFIX = "/topic/iot/telemetry/";

    /** STOMP 设备状态主题前缀：/topic/iot/device-status/{wardId}（B4.3 推送语义，简报 §1.5） */
    public static final String TOPIC_DEVICE_STATUS_PREFIX = "/topic/iot/device-status/";

    /** STOMP 告警主题前缀：/topic/iot/alarm/{wardId}（FU-M14-08 分级通知面，P2 PR-2 Task 7） */
    public static final String TOPIC_ALARM_PREFIX = "/topic/iot/alarm/";

    /** STOMP 全院运营摘要主题：/topic/iot/dashboard/global（FU-M14-13 四主题完整化，P2 PR-2 Task 11） */
    public static final String TOPIC_DASHBOARD_GLOBAL = "/topic/iot/dashboard/global";

    /** 错误留痕摘要算法：SHA-256，十六进制摘要 64 位与 raw_digest 列宽一致（DeadLetterListener 同口径） */
    public static final String DIGEST_ALGORITHM_SHA256 = "SHA-256";

    /** 错误留痕载荷截断上限：raw_payload 列宽 VARCHAR(4000)（列宽防线，超长载荷截断留痕） */
    public static final int RAW_PAYLOAD_MAX_LENGTH = 4000;

    /** 错误留痕原因截断上限：error_msg 列宽 VARCHAR(500)（列宽防线，防摘要超长致落库失败） */
    public static final int ERROR_MSG_MAX_LENGTH = 500;

    /**
     * 私有构造器：常量类禁止实例化（backend 宪法 A.2-6）。
     */
    private IotMessagingConstants() {}
}
