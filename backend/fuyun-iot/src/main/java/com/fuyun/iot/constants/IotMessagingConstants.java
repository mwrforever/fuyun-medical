package com.fuyun.iot.constants;

/**
 * IoT 消息与遥测管道常量：事件/队列/交换机命名、遥测与状态帧判别键、错误留痕截断上限的集中定义
 * （M14 词表，禁魔法值散落——backend 宪法 A.2-6）。
 *
 * <p>fy.topic 事件三件套词表与 fuyun-integration MessagingConstants 命名口径一致（q. 前缀队列、
 * iot.device.status-changed 事件，V403 已登记 event_registry）；本模块自持一份常量避免跨模块
 * 常量耦合（B.2-2 只依赖 api 契约，常量词表非 api 契约）。帧判别键为 CF-7 线格式（BRIEF-PR4-01
 * §1.3 P0 线格式契约）与状态帧 P0 契约形态的字段名。
 */
public final class IotMessagingConstants {

    /** 发布方模块标识：event_registry producer 与幂等分域 consumer_module 共用 */
    public static final String MODULE = "iot";

    /** 领域事件主交换机：全部业务事件经此路由（Topic 类型，与 M20 治理词表同源） */
    public static final String TOPIC_EXCHANGE = "fy.topic";

    /** P0 唯一发布事件：设备状态变更（V403 已登记 event_registry，P0 占位载荷随 P1 冻结） */
    public static final String EVENT_DEVICE_STATUS = "iot.device.status-changed";

    /** 本模块自事件消费队列：q.&lt;消费者模块&gt;.&lt;事件类型&gt;（治理构件声明用） */
    public static final String QUEUE_DEVICE_STATUS = "q.iot.iot.device.status-changed";

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
