package com.fuyun.iot.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.common.messaging.MessageIdempotencyService;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.api.payload.AlarmClosedPayload;
import com.fuyun.iot.api.payload.AlarmTriggeredPayload;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.service.IDashboardService;
import com.fuyun.iot.service.ITelemetryPushService;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * IoT 自事件扇出消费者（B4.3 消费侧 + P2 PR-2 Task 11 扩订阅/R1 修复）：设备状态、告警触发与
 * 告警关闭三类自事件的扇出消费执行点（本模块自事件——发布→自消费全链；多实例扇出经 RabbitMQ
 * 队列，「每消费者一队列」形态——同一事件按消费者分队列各自绑定 fy.topic 同一路由键，队列间
 * 互不竞争）。
 *
 * <p>三队列与消费者域（幂等分域红线 9：幂等键第二要素 = 消费者域标识，同事件多消费者必须
 * 分域，否则先处理方的 PROCESSED 行经 D-7 回查抑制后处理方）：
 * <ul>
 *   <li>q.iot.iot.device.status-changed（域 iot）：设备状态帧推送 + 大屏摘要变更检测；</li>
 *   <li>q.iot.iot.alarm.closed（域 iot）：大屏摘要变更检测（活跃告警计数变化）；</li>
 *   <li>q.iot-fanout.iot.alarm.triggered（域 iot-fanout，R1 修复）：大屏摘要变更检测（活跃告警
 *       计数变化）。联动链队列 q.iot.iot.alarm.triggered（IotAlarmEventListener）独立消费联动
 *       编排——两链各自消费同一事件，幂等域分列互不抑制。</li>
 * </ul>
 *
 * <p><b>确认机制（红线 4，锁定决策 6）</b>：本监听器走 RabbitMQ 容器 <b>AUTO 确认</b>
 * （宪法 A.5-5：监听方法成功返回即由容器确认，失败有界重试耗尽进 fy.dlx）；iot AMQP 主链路
 * （IotAmqpTelemetryConsumer）走 Qpid JMS 客户端确认——两套机制互不相干。幂等范式走
 * {@link MessageIdempotencyService} 标准三段式（Redis NX 前置 + received_event 唯一索引兜底）。
 *
 * <p><b>消费后动作</b>：载荷契约解析 + 结构化留痕 + STOMP/大屏推送——设备状态帧经
 * {@link ITelemetryPushService#pushDeviceStatus} 推 /topic/iot/device-status/{wardId}（载荷
 * wardId 为空时推送静默降级）；大屏摘要经 {@link IDashboardService#refreshAndPushIfChanged}
 * 变更检测推送 /topic/iot/dashboard/global（summary 变更触发形态）。推送/刷新失败按业务失败
 * 处置（settleFailure 失败收尾重抛走有界重试），成功后才登记 PROCESSED。
 *
 * <p>归 internal/ 包：容器驱动的模块内入口，禁止外部引用（backend 宪法 B.1）；Bean 注册点
 * 为 IotMessagingConfig @Import。
 */
@Slf4j
public class IotFanoutListener {

    private final MessageIdempotencyService idempotencyService;

    private final EventEnvelopeCodec codec;

    private final ObjectMapper objectMapper;

    /** STOMP 推送服务：消费后设备状态主题推送的唯一出口（/topic/iot/device-status/{wardId}） */
    private final ITelemetryPushService pushService;

    /** 运营大屏数据面服务：摘要变更检测推送出口（/topic/iot/dashboard/global，变更触发形态） */
    private final IDashboardService dashboardService;

    /**
     * 全参构造器（装配归 IotMessagingConfig @Import，backend 宪法 B.1）。
     *
     * @param idempotencyService 消费幂等构件，非空；来源：M20 治理构件装配（接口沉 common，
     *                           实现经 fuyun-app MessagingConfig @Import 已在上下文可用）
     * @param codec              信封编解码器，非空；来源：MessagingGovernanceConfig 装配
     * @param objectMapper       JSON 转换器，非空；载荷契约 record 反序列化（全局定制实例）
     * @param pushService        STOMP 推送服务，非空；来源：fuyun-app IotConfig 装配链
     * @param dashboardService   运营大屏数据面服务，非空；来源：fuyun-app IotConfig 装配链
     *                           （P2 PR-2 Task 11 扩订阅追加依赖）
     */
    public IotFanoutListener(
            MessageIdempotencyService idempotencyService,
            EventEnvelopeCodec codec,
            ObjectMapper objectMapper,
            ITelemetryPushService pushService,
            IDashboardService dashboardService) {
        this.idempotencyService = idempotencyService;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.pushService = pushService;
        this.dashboardService = dashboardService;
    }

    /**
     * 设备状态事件消费入口：解析信封 → 幂等范式 → 业务留痕 + 成功登记。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本模块消费队列的信封线格式
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_DEVICE_STATUS)
    public void onDeviceStatusChanged(Message message) {
        // 原文进 codec：__TypeId__ 头不作消费依据（CF-1 冻结约定）；不合规信封上抛交有界重试转死信
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        consumeWithIdempotency(envelope, IotMessagingConstants.MODULE, this::handleDeviceStatusChanged);
    }

    /**
     * 告警关闭事件消费入口（Task 11 扩订阅）：解析信封 → 幂等范式 → 大屏摘要变更检测推送 +
     * 成功登记。队列声明归 IotMessagingConfig（治理构件 declareConsumerQueue，事件 V1004 id 76
     * 已登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本模块消费队列的信封线格式
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_ALARM_CLOSED)
    public void onAlarmClosed(Message message) {
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        consumeWithIdempotency(envelope, IotMessagingConstants.MODULE, this::handleAlarmClosed);
    }

    /**
     * 告警触发事件扇出消费入口（P2 PR-2 Task 11 R1 修复）：独立扇出队列消费（与联动链队列同
     * 路由键分队列，幂等域 iot-fanout 与联动链 iot 分列互不抑制）→ 大屏摘要变更检测推送（新发
     * 告警使活跃告警计数变化）→ 成功登记。队列声明归 IotMessagingConfig（治理构件
     * declareConsumerQueue，事件 V1004 id 74 已登记）。
     *
     * @param message 原始消息帧，非空；来源：fy.topic 路由至本扇出队列的信封线格式
     */
    @RabbitListener(queues = IotMessagingConstants.QUEUE_ALARM_TRIGGERED_FANOUT)
    public void onAlarmTriggeredFanout(Message message) {
        EventEnvelope envelope = codec.fromJson(new String(message.getBody(), StandardCharsets.UTF_8));
        consumeWithIdempotency(
                envelope, IotMessagingConstants.FANOUT_CONSUMER_MODULE, this::handleAlarmTriggeredFanout);
    }

    /**
     * 标准幂等范式骨架（三消费入口共用）：重复投递跳过即 AUTO 确认；业务执行成功登记
     * PROCESSED；业务失败 settleFailure 失败收尾（释放前置键 + FAILED 留痕，双保留不遮蔽）
     * 后重抛交有界重试。消费者域标识由调用口传入（同事件多消费者分域——幂等键第二要素）。
     *
     * @param envelope       已解析的合规信封，非空
     * @param consumerModule 消费者域标识（幂等键第二要素 + received_event 登记行），非空
     * @param business       消费业务体（载荷解析与推送/刷新动作），非空
     */
    private void consumeWithIdempotency(
            EventEnvelope envelope, String consumerModule, Consumer<EventEnvelope> business) {
        // 标准范式①：重复投递（NX 失败且回查确认已处理）直接返回跳过，即 AUTO 确认
        if (!idempotencyService.tryAcquire(envelope.eventId(), consumerModule)) {
            log.info(
                    "重复投递跳过：consumerModule={}，event_id={}，eventType={}",
                    consumerModule,
                    envelope.eventId(),
                    envelope.eventType());
            return;
        }
        // 信封五要素在业务前构造一次：成功登记与失败留痕共用（两处字段映射不漂移）
        ReceivedEventRecord record = new ReceivedEventRecord(
                envelope.eventId(), envelope.eventType(), envelope.producer(), envelope.occurredAt(), consumerModule);
        try {
            business.accept(envelope);
            // 标准范式②：成功登记 received_event（唯一索引兜底并发，前次失败行升级为已处理）
            idempotencyService.recordProcessed(record);
        } catch (RuntimeException e) {
            // 标准范式③：释放前置键 + FAILED 留痕（W-6③ 双保留，异常链不遮蔽 e），上抛走有界重试进 fy.dlx
            idempotencyService.settleFailure(record, e);
            throw e;
        }
    }

    /**
     * 设备状态消费业务：载荷契约解析 + 结构化日志留痕 + STOMP 设备状态主题推送 + 大屏摘要
     * 变更检测推送。
     *
     * <p>推送语义：载荷 wardId 为空时设备状态帧推送静默降级（简报 §1.5，P0 状态帧契约不含
     * wardId 属预期场景）；大屏摘要刷新与 wardId 无关（全院计数面）恒执行。推送/刷新失败按
     * 业务失败处置（异常上抛由范式③失败收尾 FAILED 留痕后重抛交有界重试）。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 DeviceStatusEvent 契约不符（字段缺失或类型错误）——
     *                               按消费失败处置，禁止静默吞错
     */
    private void handleDeviceStatusChanged(EventEnvelope envelope) {
        DeviceStatusEvent event;
        try {
            event = objectMapper.treeToValue(envelope.payload(), DeviceStatusEvent.class);
        } catch (JsonProcessingException e) {
            // 内部断言：模块自产事件载荷与 record 契约不符属发布方编程错误，非用户输入路径；
            // 上抛由范式③失败收尾（FAILED 留痕后重抛），最终转死信留痕
            throw new IllegalStateException("设备状态事件载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "设备状态自事件消费完成：deviceId={}，status={}，wardId={}，event_id={}，traceId={}",
                event.deviceId(),
                event.status(),
                event.wardId(),
                envelope.eventId(),
                envelope.traceId());
        // STOMP 设备状态主题推送（wardId 空由推送服务静默降级；失败上抛走释放重推，范式③承接）
        pushService.pushDeviceStatus(event);
        // 大屏摘要变更检测推送（在线/离线计数变化触发源；变更判定与缓存回写在服务内闭环）
        dashboardService.refreshAndPushIfChanged();
    }

    /**
     * 告警触发扇出消费业务（R1 修复）：载荷契约解析 + 结构化日志留痕 + 大屏摘要变更检测推送
     * （新发告警使活跃告警计数变化）。不推告警主题帧——告警分级通知帧由告警引擎触发链既有直推
     * 面承接（/topic/iot/alarm/{wardId}，Task 7 冻结面），本链路只承载大屏摘要变更触发。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 AlarmTriggeredPayload 契约不符（字段缺失或类型错误）——
     *                               按消费失败处置，禁止静默吞错
     */
    private void handleAlarmTriggeredFanout(EventEnvelope envelope) {
        AlarmTriggeredPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), AlarmTriggeredPayload.class);
        } catch (JsonProcessingException e) {
            // 内部断言：模块自产事件载荷与 record 契约不符属发布方编程错误，非用户输入路径；
            // 上抛由范式③失败收尾（FAILED 留痕后重抛），最终转死信留痕
            throw new IllegalStateException("告警触发事件载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "告警触发自事件扇出消费完成：alarmNo={}，deviceId={}，wardId={}，event_id={}，traceId={}",
                payload.alarmNo(),
                payload.deviceId(),
                payload.wardId(),
                envelope.eventId(),
                envelope.traceId());
        // 大屏摘要变更检测推送（活跃告警计数变化触发源）
        dashboardService.refreshAndPushIfChanged();
    }

    /**
     * 告警关闭消费业务：载荷契约解析 + 结构化日志留痕 + 大屏摘要变更检测推送（活跃告警计数
     * 变化触发源）。
     *
     * <p>不推告警主题帧：关闭面（M05/M16 复位与统计）按事件契约消费方各自承接，本监听器只
     * 承载大屏摘要变更触发（四主题中告警主题的关闭帧推送无冻结契约，不臆造载荷）。
     *
     * @param envelope 已解析的合规信封，非空
     * @throws IllegalStateException 载荷与 AlarmClosedPayload 契约不符（字段缺失或类型错误）——
     *                               按消费失败处置，禁止静默吞错
     */
    private void handleAlarmClosed(EventEnvelope envelope) {
        AlarmClosedPayload payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), AlarmClosedPayload.class);
        } catch (JsonProcessingException e) {
            // 内部断言：模块自产事件载荷与 record 契约不符属发布方编程错误，非用户输入路径；
            // 上抛由范式③失败收尾（FAILED 留痕后重抛），最终转死信留痕
            throw new IllegalStateException("告警关闭事件载荷与契约不符：event_id=" + envelope.eventId(), e);
        }
        log.info(
                "告警关闭自事件消费完成：alarmNo={}，deviceId={}，wardId={}，event_id={}，traceId={}",
                payload.alarmNo(),
                payload.deviceId(),
                payload.wardId(),
                envelope.eventId(),
                envelope.traceId());
        // 大屏摘要变更检测推送（活跃告警计数变化触发源）
        dashboardService.refreshAndPushIfChanged();
    }
}
