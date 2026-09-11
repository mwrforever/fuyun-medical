package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.IConsumeErrorLogService;
import com.fuyun.iot.service.IDeviceStatusService;
import com.fuyun.iot.service.ITelemetryIngestService;
import jakarta.jms.BytesMessage;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSException;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

/**
 * AMQP 遥测消费者单测（BRIEF-PR4-01 §3 internal/IotAmqpTelemetryConsumer 行，fake JMS 全覆盖）。
 *
 * <p>业务意图：以 Mockito 伪造 ConnectionFactory/JMS 上下文覆盖消费链六类契约——①正常批：遥测帧
 * 入攒批、落库成功后仅批末消息 acknowledge（JMS CLIENT_ACKNOWLEDGE 会话级统一确认本批）；②毒丸
 * 隔离：解析失败帧落 iot_consume_error_log（stage=PARSE）后确认抛弃，不阻塞队列；③状态帧即时
 * 处理：apply 返回档案 wardId 时构造含 wardId 的事件触发状态事件回调（B4.3 发布器接线点）并
 * 即时确认，返回 null 仅确认不发布；④断链重建：连接异常后销毁旧连接、以<b>新时间戳凭证</b>重建
 * （IoTDA 拒绝超 5 分钟旧时间戳，failover 透明重连不刷新时间戳）；⑤退避节奏：初始延迟起步指数
 * 退避至上限封顶（测试用毫秒级参数验证节奏公式，record 型假 sleeper 避免真实睡眠）；⑥stop 排空：
 * 先停拉取，在途帧经攒批器停机排空后统一确认；⑦失败重建（重投域语义）：攒批落库失败与状态帧
 * apply 业务失败均触发全局会话重建——CLIENT_ACKNOWLEDGE 会话级累计确认下"仅不确认继续消费"会让
 * 失败帧被后续成功批确认静默吞掉，必须销毁会话令未确认交付回归 broker 重投域（重投帧由唯一约束
 * 幂等去重）。测试凭证均为无意义假值（测试资产，与任何真实 IOTDA 凭证无关）。
 */
class IotAmqpTelemetryConsumerTest {

    /** 订阅队列地址（与生产配置同型，值原样传给 JMS createQueue） */
    private static final String QUEUE_ADDRESS = "it.iot.telemetry";

    /** 测试资产假 accessKey（仅具单测意义） */
    private static final String TEST_ACCESS_KEY = "test-access-key";

    /** 测试资产假凭证（仅具单测意义，仅可断言口令形态，禁断言真实值与日志输出） */
    private static final String TEST_ACCESS_SECRET = "test-access-secret";

    /** 测试用 AMQP 参数：batchSize=2/interval=50ms 便于批确认断言；退避 10ms→30ms 毫秒级验证节奏 */
    private static final IotProperties.Amqp TEST_AMQP = new IotProperties.Amqp(
            true,
            "amqp://127.0.0.1:5672",
            TEST_ACCESS_KEY,
            TEST_ACCESS_SECRET,
            List.of(QUEUE_ADDRESS),
            1000,
            2,
            Duration.ofMillis(50),
            16,
            Duration.ofMillis(10),
            Duration.ofMillis(30));

    /** 测试轮询等待上限：消费/攒批为异步链路，3s 容忍调度抖动 */
    private static final long AWAIT_MILLIS = 3000L;

    private ConnectionFactory connectionFactory;

    private JMSContext jmsContext;

    private JMSConsumer jmsConsumer;

    private ITelemetryIngestService ingestService;

    private IConsumeErrorLogService errorLogService;

    private IDeviceStatusService deviceStatusService;

    private TelemetryBatchAssembler assembler;

    /** 状态事件回调收集器：构造期注入消费者（发布器接线点的记录型替身） */
    private final List<DeviceStatusEvent> statusEventsPublished = new CopyOnWriteArrayList<>();

    /** 记录型假 sleeper：只记不睡（避免真实 sleep，退避节奏断言载体） */
    private List<Long> recordedDelays;

    /** 可进动假时钟：每次 millis() 前进 1s（新时间戳凭证断言载体） */
    private IotAmqpTelemetryConsumerTest.MutableClock mutableClock;

    private IotAmqpTelemetryConsumer consumer;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(ConnectionFactory.class);
        jmsContext = mock(JMSContext.class);
        jmsConsumer = mock(JMSConsumer.class);
        Queue queue = mock(Queue.class);
        ingestService = mock(ITelemetryIngestService.class);
        errorLogService = mock(IConsumeErrorLogService.class);
        deviceStatusService = mock(IDeviceStatusService.class);

        when(jmsContext.createQueue(QUEUE_ADDRESS)).thenReturn(queue);
        when(jmsContext.createConsumer(queue)).thenReturn(jmsConsumer);
        doAnswer(invocation -> null).when(jmsContext).setExceptionListener(any());
        recordedDelays = new CopyOnWriteArrayList<>();
        mutableClock = new MutableClock(new AtomicLong(1_700_000_000_000L));

        assembler = new TelemetryBatchAssembler(ingestService, propsOf(TEST_AMQP));
        assembler.start();
        consumer = new IotAmqpTelemetryConsumer(
                propsOf(TEST_AMQP),
                connectionFactory,
                assembler,
                errorLogService,
                deviceStatusService,
                mutableClock,
                recordedDelays::add,
                statusEventsPublished::add);
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        assembler.stop();
    }

    @Test
    @DisplayName("正常批确认：遥测帧入攒批落库后仅批末消息 acknowledge（会话级统一确认本批全部）")
    void acknowledgesLastMessageOnlyAfterBatchIngest() throws Exception {
        Message firstFrame = bytesMessage(telemetryJson("it-dev-001", "vital.heart-rate", "72"));
        Message lastFrame = bytesMessage(telemetryJson("it-dev-001", "vital.spo2", "98"));
        stubContextCreation();
        when(jmsConsumer.receive(anyLong())).thenReturn(firstFrame, lastFrame).thenAnswer(this::idleAnswer);

        consumer.start();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StandardTelemetryMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(ingestService, timeout(AWAIT_MILLIS)).ingest(captor.capture());
        assertThat(captor.getValue()).as("两帧遥测按接收序整批落库").hasSize(2);
        // 客户端确认契约：仅批末消息执行 acknowledge——CLIENT_ACKNOWLEDGE 会话级累计确认本批全部
        verify(lastFrame, timeout(AWAIT_MILLIS)).acknowledge();
        verify(firstFrame, timeout(500).times(0)).acknowledge();
    }

    @Test
    @DisplayName("毒丸隔离：解析失败帧落 PARSE 错误日志后确认抛弃，不阻塞后续帧消费")
    void poisonsUnparsableFramesToErrorLogAndAcknowledges() throws Exception {
        Message nonJsonFrame = bytesMessage("not-a-json-frame{{{");
        Message missingFieldFrame = bytesMessage("{\"deviceId\":\"it-dev-001\"}");
        stubContextCreation();
        when(jmsConsumer.receive(anyLong()))
                .thenReturn(nonJsonFrame, missingFieldFrame)
                .thenAnswer(this::idleAnswer);

        consumer.start();

        verify(errorLogService, timeout(AWAIT_MILLIS).times(2))
                .recordParseFailure(eq(QUEUE_ADDRESS), anyString(), eq("PARSE"), anyString());
        verify(nonJsonFrame, timeout(AWAIT_MILLIS)).acknowledge();
        verify(missingFieldFrame, timeout(AWAIT_MILLIS)).acknowledge();
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("状态帧即时处理：apply 返回档案 wardId 时构造含 wardId 的事件触发回调（发布器接线点）并即时确认")
    void appliesStatusFrameAndFiresEventSinkWhenDeviceValid() throws Exception {
        Message statusFrame = bytesMessage(
                "{\"deviceId\":\"it-dev-001\",\"status\":\"OFFLINE\",\"occurredAt\":\"2026-09-10T00:00:00Z\"}");
        stubContextCreation();
        when(jmsConsumer.receive(anyLong())).thenReturn(statusFrame).thenAnswer(this::idleAnswer);
        // P0 状态帧契约不含 wardId（解析产物恒 null）：档案 wardId 由 apply 返回值补全（审核 F-2）
        when(deviceStatusService.apply(any(DeviceStatusEvent.class))).thenReturn(1001L);

        consumer.start();

        ArgumentCaptor<DeviceStatusEvent> eventCaptor = ArgumentCaptor.forClass(DeviceStatusEvent.class);
        verify(deviceStatusService, timeout(AWAIT_MILLIS)).apply(eventCaptor.capture());
        assertThat(eventCaptor.getValue().deviceId()).isEqualTo("it-dev-001");
        assertThat(eventCaptor.getValue().status()).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(eventCaptor.getValue().wardId())
                .as("apply 入参为解析产物，wardId 恒 null")
                .isNull();
        assertThat(statusEventsPublished).as("有效设备的状态事件必须触发回调（构造期接线发布器）").hasSize(1);
        assertThat(statusEventsPublished.get(0).wardId())
                .as("发布事件必须携带档案 wardId（/topic/iot/device-status/{wardId} 路由数据源）")
                .isEqualTo(1001L);
        verify(statusFrame, timeout(AWAIT_MILLIS)).acknowledge();
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("状态帧无效设备：apply 返回 null 时仅确认不触发事件回调（档案未种子的容错口径）")
    void skipsEventSinkWhenStatusAppliesToUnknownDevice() throws Exception {
        Message statusFrame = bytesMessage(
                "{\"deviceId\":\"ghost-dev\",\"status\":\"ONLINE\",\"occurredAt\":\"2026-09-10T00:00:00Z\"}");
        stubContextCreation();
        when(jmsConsumer.receive(anyLong())).thenReturn(statusFrame).thenAnswer(this::idleAnswer);
        when(deviceStatusService.apply(any(DeviceStatusEvent.class))).thenReturn(null);

        consumer.start();

        verify(statusFrame, timeout(AWAIT_MILLIS)).acknowledge();
        assertThat(statusEventsPublished).as("无效设备不发布状态事件").isEmpty();
    }

    @Test
    @DisplayName("断链重建：消费异常后销毁旧连接，以新时间戳凭证重建（IoTDA 时间戳语义，杜绝透明重连）")
    void rebuildsConnectionWithFreshTimestampCredentialAfterFailure() throws Exception {
        JMSContext staleContext = mock(JMSContext.class);
        Queue staleQueue = mock(Queue.class);
        when(staleContext.createQueue(QUEUE_ADDRESS)).thenReturn(staleQueue);
        when(staleContext.createConsumer(staleQueue)).thenReturn(jmsConsumer);
        doAnswer(invocation -> null).when(staleContext).setExceptionListener(any());
        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        when(connectionFactory.createContext(
                        eq(TEST_ACCESS_KEY), passwordCaptor.capture(), eq(JMSContext.CLIENT_ACKNOWLEDGE)))
                .thenReturn(staleContext, jmsContext);
        // 首次 receive 即断链：supervisor 必须销毁旧连接并重建，恢复后可继续消费
        when(jmsConsumer.receive(anyLong()))
                .thenThrow(new JMSRuntimeException("connection lost"))
                .thenAnswer(this::idleAnswer);

        consumer.start();

        verify(connectionFactory, timeout(AWAIT_MILLIS).times(2))
                .createContext(eq(TEST_ACCESS_KEY), anyString(), anyInt());
        // 重建语义一：旧连接已销毁（close）——worker 在退避重建前先行关闭旧连接，await 建链两次后必然已发生
        verify(staleContext, timeout(AWAIT_MILLIS)).close();
        // 重建语义二：新凭证含新时间戳（IoTDA 拒绝超 5 分钟旧时间戳，failover 透明重连不刷新时间戳）
        assertThat(passwordCaptor.getAllValues()).hasSize(2);
        String oldPassword = passwordCaptor.getAllValues().get(0);
        String rebuiltPassword = passwordCaptor.getAllValues().get(1);
        assertThat(oldPassword)
                .as("口令 = accessSecret + 13 位毫秒时间戳")
                .startsWith(TEST_ACCESS_SECRET)
                .hasSize(TEST_ACCESS_SECRET.length() + 13);
        assertThat(rebuiltPassword)
                .as("重建口令同形态且时间戳严格更新（销毁重建而非透明重连）")
                .startsWith(TEST_ACCESS_SECRET)
                .hasSize(TEST_ACCESS_SECRET.length() + 13);
        assertThat(Long.parseLong(rebuiltPassword.substring(TEST_ACCESS_SECRET.length())))
                .isGreaterThan(Long.parseLong(oldPassword.substring(TEST_ACCESS_SECRET.length())));
        // 重建成功后 connected 载体恢复 1（断链起点清零）
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (consumer.connectedFlag().get() != 1L && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(consumer.connectedFlag().get()).as("重建成功后 connected 恢复 1").isEqualTo(1L);
        assertThat(consumer.disconnectSinceMillis().get())
                .as("断链起点已清零（断链时长指标回零）")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("退避节奏：连续断链按初始延迟起步指数退避至上限封顶（3s→30s 公式的毫秒级验证）")
    void backsOffExponentiallyUpToMaxDelayOnRepeatedFailures() {
        when(connectionFactory.createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE)))
                .thenThrow(new JMSRuntimeException("broker down"));
        consumer.start();
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (consumer.reconnectCount().get() < 5 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        consumer.stop();
        assertThat(recordedDelays).as("退避序列 = 10ms 起步指数增长至 30ms 封顶").containsSubsequence(10L, 20L, 30L, 30L);
        assertThat(recordedDelays).allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(30L));
    }

    @Test
    @DisplayName("stop 排空：先停拉取，在途帧经攒批器停机排空后统一确认（宪法 A.5-15 停机顺序）")
    void drainsInFlightFramesOnGracefulStop() throws Exception {
        // 大 batchSize + 长时间窗：拉取阶段不自动刷批，滞留条目只能由停机排空路径提交
        IotProperties.Amqp holdAll = new IotProperties.Amqp(
                true,
                "amqp://127.0.0.1:5672",
                TEST_ACCESS_KEY,
                TEST_ACCESS_SECRET,
                List.of(QUEUE_ADDRESS),
                1000,
                10,
                Duration.ofHours(1),
                16,
                Duration.ofMillis(10),
                Duration.ofMillis(30));
        TelemetryBatchAssembler holdingAssembler = new TelemetryBatchAssembler(ingestService, propsOf(holdAll));
        holdingAssembler.start();
        IotAmqpTelemetryConsumer holdingConsumer = new IotAmqpTelemetryConsumer(
                propsOf(holdAll),
                connectionFactory,
                holdingAssembler,
                errorLogService,
                deviceStatusService,
                mutableClock,
                recordedDelays::add,
                statusEventsPublished::add);
        try {
            Message firstFrame = bytesMessage(telemetryJson("it-dev-001", "vital.heart-rate", "72"));
            Message secondFrame = bytesMessage(telemetryJson("it-dev-001", "vital.spo2", "98"));
            stubContextCreation();
            when(jmsConsumer.receive(anyLong()))
                    .thenReturn(firstFrame, secondFrame)
                    .thenAnswer(this::idleAnswer);

            holdingConsumer.start();
            // 拉取完成证据：两帧均已被消费线程读取进入攒批（尚未落库未确认）
            verify(firstFrame, timeout(AWAIT_MILLIS)).getBody(byte[].class);
            verify(secondFrame, timeout(AWAIT_MILLIS)).getBody(byte[].class);
            verify(secondFrame, timeout(300).times(0)).acknowledge();

            // 优雅停机：按 Spring 真实停止顺序编排——消费者（phase=1）先停拉取，攒批器（phase=0）后停排空
            // 在途批；此序保证排空时不再有新帧入队（宪法 A.5-15）
            holdingConsumer.stop();
            holdingAssembler.stop();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<StandardTelemetryMessage>> captor = ArgumentCaptor.forClass(List.class);
            verify(ingestService, timeout(AWAIT_MILLIS)).ingest(captor.capture());
            assertThat(captor.getValue()).as("在途帧停机排空零丢失").hasSize(2);
            verify(secondFrame, timeout(AWAIT_MILLIS)).acknowledge();
            verify(firstFrame, never()).acknowledge();
        } finally {
            holdingConsumer.stop();
            holdingAssembler.stop();
        }
    }

    @Test
    @DisplayName("生命周期契约：自动启动、phase 后于攒批器（先停拉取后排空的顺序基础）")
    void exposesLifecycleContractForGracefulShutdownOrder() {
        assertThat(consumer.isAutoStartup()).as("SmartLifecycle 自动启动（宪法 A.5-9）").isTrue();
        // Spring SmartLifecycle 语义：启动按 phase 升序、停止按降序——消费者 phase 必须更大，
        // 才能"先攒批接帧后拉取"启动、"先停拉取后排空在途批"停机（宪法 A.5-15）
        assertThat(consumer.getPhase())
                .as("消费者 phase=1 必须大于攒批器 phase=0（启动先攒批接帧后拉取，停止先停拉取后排空）")
                .isGreaterThan(assembler.getPhase());
    }

    @Test
    @DisplayName("文本消息兼容：TextMessage 形态的帧同样可解析消费（四路同构的载体兼容）")
    void consumesTextMessageShapedFrame() throws Exception {
        TextMessage textFrame = mock(TextMessage.class);
        when(textFrame.getText()).thenReturn(telemetryJson("it-dev-001", "vital.temp", "36.8"));
        stubContextCreation();
        when(jmsConsumer.receive(anyLong())).thenReturn(textFrame).thenAnswer(this::idleAnswer);

        consumer.start();

        verify(ingestService, timeout(AWAIT_MILLIS)).ingest(anyList());
    }

    @Test
    @DisplayName("异常监听回调（running）：直调 onException 全局标记断链（connected 置 0+断链起点记录）并关闭在册连接")
    void onExceptionMarksGlobalDisconnectAndClosesWorkerContextsWhileRunning() throws Exception {
        // 首建链成功、后续建链一律失败：钉住 onException 自身的断链标记效果（防 worker 立即重连把
        // connected 翻回 1 造成断言竞态），重连本身由 supervisor 既有用例覆盖
        when(connectionFactory.createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE)))
                .thenReturn(jmsContext)
                .thenThrow(new JMSRuntimeException("rebuild blocked for assertion stability"));
        stubReceiveIdle();
        consumer.start();
        awaitConnectedFlag(1L);

        // 直调 ExceptionListener 回调（JMS 异常回调无法定位具体连接，契约=全局标记断链+关闭全部在册连接）
        consumer.onException(new JMSException("connection lost"));

        assertThat(consumer.connectedFlag().get()).as("断链标记：connected 载体置 0").isEqualTo(0L);
        assertThat(consumer.disconnectSinceMillis().get())
                .as("断链起点仅在首个失败时记录（断链时长指标起算点）")
                .isPositive();
        // 全局关闭在册连接：消费线程经 receive 异常进入 supervisor 重建路径（对已关闭线程幂等无害）
        verify(jmsContext).close();
    }

    @Test
    @DisplayName("异常监听回调守卫分支（非 running）：已 stop 后直调 onException 零副作用（断链标记不动）")
    void onExceptionIsNoOpWhenConsumerAlreadyStopped() throws Exception {
        stubContextCreation();
        stubReceiveIdle();
        consumer.start();
        awaitConnectedFlag(1L);

        // 优雅停机（等消费线程退出，running=false）后再直调回调：守卫分支必须直接返回
        consumer.stop();
        consumer.onException(new JMSException("connection lost after stop"));

        assertThat(consumer.connectedFlag().get())
                .as("停机后回调不改写断链标记（保持停机前的连接正常态）")
                .isEqualTo(1L);
        assertThat(consumer.disconnectSinceMillis().get()).as("停机后回调不记录断链起点").isEqualTo(0L);
    }

    @Test
    @DisplayName("落库失败重建（重投域语义）：攒批落库失败触发旧会话销毁与 supervisor 重建，重投帧在新会话成功后确认")
    void flushFailureTriggersSessionRebuildAndRedeliveredFrameIsReingested() throws Exception {
        // 独立的第一代上下文/消费者 mock：与重建后的第二代分离，杜绝共享 mock 的投递次序竞态
        JMSContext staleContext = mock(JMSContext.class);
        JMSConsumer staleConsumer = mock(JMSConsumer.class);
        Queue staleQueue = mock(Queue.class);
        when(staleContext.createQueue(QUEUE_ADDRESS)).thenReturn(staleQueue);
        when(staleContext.createConsumer(staleQueue)).thenReturn(staleConsumer);
        doAnswer(invocation -> null).when(staleContext).setExceptionListener(any());

        Message frame = bytesMessage(telemetryJson("it-dev-001", "vital.glucose", "5.6"));
        // 首建链返回第一代上下文，落库失败触发重建后返回第二代上下文（新会话 = 重投域语义的载体）
        when(connectionFactory.createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE)))
                .thenReturn(staleContext, jmsContext);
        // 第一代：仅投递一帧后空闲轮询（攒批落库即将失败）；第二代：模拟 broker 重投同帧后再空闲
        when(staleConsumer.receive(anyLong())).thenReturn(frame).thenAnswer(this::idleAnswer);
        when(jmsConsumer.receive(anyLong())).thenReturn(frame).thenAnswer(this::idleAnswer);
        // 落库首刷失败、重投批次成功（装饰器时序 = 失败批零确认 → 重建 → 重投 → 成功确认）
        when(ingestService.ingest(anyList()))
                .thenThrow(new IllegalStateException("数据库瞬断"))
                .thenReturn(1);

        consumer.start();

        // 断言 1：失败触发会话重建——旧会话销毁（未确认交付回归 broker 重投域）+ 建链两次（supervisor 介入）
        verify(staleContext, timeout(AWAIT_MILLIS)).close();
        verify(connectionFactory, timeout(AWAIT_MILLIS).times(2))
                .createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE));
        // 断言 2：失败批零确认 + 重投批成功后确认（acknowledge 恰一次 = 重投域语义的消费者侧行为）
        assertThat(failureCountEventually()).as("攒批失败计数递增（落库失败真实发生）").isTrue();
        verify(ingestService, timeout(AWAIT_MILLIS).times(2)).ingest(anyList());
        verify(frame, timeout(AWAIT_MILLIS)).acknowledge();
    }

    @Test
    @DisplayName("状态帧 apply 失败重建：旧会话销毁与 supervisor 重建，重投状态帧成功后确认且事件仅发布一次")
    void statusFrameApplyFailureTriggersRebuildAndRedeliveredFrameSucceeds() throws Exception {
        JMSContext staleContext = mock(JMSContext.class);
        JMSConsumer staleConsumer = mock(JMSConsumer.class);
        Queue staleQueue = mock(Queue.class);
        when(staleContext.createQueue(QUEUE_ADDRESS)).thenReturn(staleQueue);
        when(staleContext.createConsumer(staleQueue)).thenReturn(staleConsumer);
        doAnswer(invocation -> null).when(staleContext).setExceptionListener(any());

        Message statusFrame = bytesMessage(
                "{\"deviceId\":\"it-dev-001\",\"status\":\"OFFLINE\",\"occurredAt\":\"2026-09-10T00:00:00Z\"}");
        when(connectionFactory.createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE)))
                .thenReturn(staleContext, jmsContext);
        // 第一代投递状态帧（apply 即将失败）；第二代重投同状态帧（broker 重投域语义）后空闲
        when(staleConsumer.receive(anyLong())).thenReturn(statusFrame).thenAnswer(this::idleAnswer);
        when(jmsConsumer.receive(anyLong())).thenReturn(statusFrame).thenAnswer(this::idleAnswer);
        // apply 首调失败（业务异常域）、重投后成功并返回档案 wardId
        when(deviceStatusService.apply(any(DeviceStatusEvent.class)))
                .thenThrow(new IllegalStateException("档案库瞬断"))
                .thenReturn(1001L);

        consumer.start();

        // 断言 1：业务失败触发会话重建——旧会话销毁 + 建链两次（走 supervisor 退避重建路径）
        verify(staleContext, timeout(AWAIT_MILLIS)).close();
        verify(connectionFactory, timeout(AWAIT_MILLIS).times(2))
                .createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE));
        // 断言 2：重投状态帧在新会话成功——apply 恰两次（失败 + 重投成功），确认与事件发布仅成功侧执行
        verify(deviceStatusService, timeout(AWAIT_MILLIS).times(2)).apply(any(DeviceStatusEvent.class));
        verify(statusFrame, timeout(AWAIT_MILLIS)).acknowledge();
        assertThat(statusEventsPublished).as("状态事件仅由重投成功侧发布一次").hasSize(1);
        assertThat(statusEventsPublished.get(0).wardId())
                .as("重投成功事件携带档案 wardId")
                .isEqualTo(1001L);
    }

    /** 轮询等待攒批失败计数达到 1（失败计数在 flush 线程异步链路递增，存在微小相位差）。 */
    private boolean failureCountEventually() throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (assembler.flushFailureCount().get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        return assembler.flushFailureCount().get() == 1L;
    }

    /** 桩：receive 空闲轮询（短暂休眠返回 null，模拟 broker 无消息） */
    private void stubReceiveIdle() {
        when(jmsConsumer.receive(anyLong())).thenAnswer(this::idleAnswer);
    }

    /** 轮询等待 connected 载体到达目标值（异步建链，3s 容忍调度抖动） */
    private void awaitConnectedFlag(long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (consumer.connectedFlag().get() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(consumer.connectedFlag().get())
                .as("等待 connected 载体到达 " + expected)
                .isEqualTo(expected);
    }

    /** 桩：建链工厂返回共享 JMS 上下文（口令 = accessSecret + 时间戳形态，单测不断言其值） */
    private void stubContextCreation() {
        when(connectionFactory.createContext(eq(TEST_ACCESS_KEY), anyString(), eq(JMSContext.CLIENT_ACKNOWLEDGE)))
                .thenReturn(jmsContext);
    }

    /** 空闲轮询 Answer：短暂休眠后返回 null（模拟 broker 无消息；中断时提前返回驱动停机） */
    private Object idleAnswer(InvocationOnMock invocation) throws InterruptedException {
        Thread.sleep(20);
        return null;
    }

    /** 构造字节消息 mock（getBody 返回 UTF-8 载荷） */
    private static BytesMessage bytesMessage(String payload) throws jakarta.jms.JMSException {
        BytesMessage message = mock(BytesMessage.class);
        when(message.getBody(byte[].class)).thenReturn(payload.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    /** 构造 CF-7 遥测帧 JSON（UTC 当前时刻） */
    private static String telemetryJson(String deviceId, String metricCode, String value) {
        return "{\"deviceId\":\"" + deviceId + "\",\"metricCode\":\"" + metricCode + "\",\"value\":\"" + value
                + "\",\"occurredAt\":\"" + Instant.now() + "\",\"quality\":\"GOOD\",\"source\":\"IOTDA\"}";
    }

    /** 以嵌套 Amqp 构造完整 IotProperties（fallback 未启用，token 传 null 合法） */
    private static IotProperties propsOf(IotProperties.Amqp amqp) {
        return new IotProperties(amqp, new IotProperties.Fallback(null));
    }

    /**
     * 可进动假时钟：每次 millis() 前进 1 秒（新时间戳凭证断言载体，杜绝两次建链同毫秒）。
     */
    static final class MutableClock extends Clock {

        private final AtomicLong millis;

        MutableClock(AtomicLong millis) {
            this.millis = millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public long millis() {
            return millis.getAndAdd(1000L);
        }
    }
}
