package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.api.payload.CommandCompletedPayload;
import com.fuyun.iot.cache.IotSeqGate;
import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.dto.ConfirmChallengeRequest;
import com.fuyun.iot.dto.IssueCommandRequest;
import com.fuyun.iot.entity.IotCommandLogEntity;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotProductCommandEntity;
import com.fuyun.iot.enums.CommandDeliverMode;
import com.fuyun.iot.enums.CommandSafetyLevel;
import com.fuyun.iot.enums.CommandStatus;
import com.fuyun.iot.enums.DeviceStatus;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame.CommandResultFrame;
import com.fuyun.iot.mapper.IotCommandLogMapper;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotProductCommandMapper;
import com.fuyun.iot.properties.CommandProperties;
import com.fuyun.iot.registry.CommandRef;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.RegistryException;
import com.fuyun.iot.vo.CommandLogVO;
import com.fuyun.iot.vo.ConfirmChallengeVO;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 命令下发编排器单测（P2 PR-2 Task 8 Step 2，五步编排时序）：凭证一次性消费（GETDEL 缺失/过期/
 * 已用同判 IOT-1015、载荷目标不匹配防换设备）、白名单复检默认拒、在线同步成功回执终态+事件、
 * 同步等待超时 TIMEOUT、注册中心异常 FAILED+IOT-1022、离线走 ASYNC 置 ISSUED 待结果帧、
 * 结果回推帧 DELIVERED 中间态与终态（终态 CAS+iot.command.completed 事件）与未知命令容错。
 *
 * <p>同步等待形态实测（Task 8）：P0 仓库无既有 AMQP 同步等待先例，按 brief 允许形态采用
 * CompletableFuture 有界等待（禁 Thread.sleep 轮询）；超时用例以毫秒级 syncTimeout + 阻塞桩
 * 注册中心驱动，无真实睡眠轮询。凭证签发门槛（白名单/治疗级/离线拒签）归
 * {@link com.fuyun.iot.service.impl.CommandServiceImplTest}。真实 SQL 归 IT 回归。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandDispatcherTest {

    private static final String DEVICE_ID = "dev-001";

    private static final String COMMAND_NAME = "setWorkMode";

    private static final String SNAPSHOT_KEY = "fy:iot:snapshot:device-status:" + DEVICE_ID;

    private static final String COMMAND_NO = "CMD2026092600001";

    private static final String OPERATOR = "nurse-01";

    private static final String TRACE_ID = "trace-001";

    /** 同步超时用例的桩注册中心放行闸（超时判定后放行阻塞线程，防守护线程滞留） */
    private static final CountDownLatch RECEIPT_GATE = new CountDownLatch(1);

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IotProductCommandMapper commandMapper;

    @Mock
    private IotCommandLogMapper commandLogMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private IotSeqGate seqGate;

    @Mock
    private IotDeviceRegistry registry;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Captor
    private ArgumentCaptor<IotCommandLogEntity> rowCaptor;

    @Captor
    private ArgumentCaptor<IotDomainEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<String> payloadCaptor;

    private CommandDispatcher dispatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最近一次签发捕获的凭证载荷原文（下发用例回灌 GETDEL，避免与载荷格式耦合） */
    private String signedPayload;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次，覆盖门槛链三实体）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotCommandLogEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotDeviceEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotProductCommandEntity.class);
    }

    @AfterAll
    static void releaseReceiptGate() {
        // 兜底放行超时用例的阻塞桩线程（守护池，逐用例已各自放行）
        RECEIPT_GATE.countDown();
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(seqGate.nextCommandNo()).thenReturn(COMMAND_NO);
        when(commandLogMapper.casTerminal(any(), any(), any(), any(), any())).thenReturn(1);
        when(commandLogMapper.casDelivered(any())).thenReturn(1);
        dispatcher = new CommandDispatcher(
                deviceMapper,
                commandMapper,
                commandLogMapper,
                redisTemplate,
                registry,
                seqGate,
                events,
                new TransactionTemplate(transactionManager),
                objectMapper,
                new CommandProperties(Duration.ofMillis(80), false));
    }

    // ---------------------------------------------------------------- 步骤③凭证消费（一次性）

    @Test
    @DisplayName("凭证缺失/过期/已用：GETDEL 为空拒绝（IOT-1015，400）——缺失与已消费同判")
    void dispatchRejectsMissingOrConsumedChallenge() {
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:chg-gone")).thenReturn(null);

        assertThatThrownBy(() -> dispatch("chg-gone")).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(IotErrorCode.COMMAND_CONFIRM_INVALID);
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
        verifyNoInteractions(commandLogMapper);
    }

    @Test
    @DisplayName("凭证一次性：同一 challengeId 第二次下发 GETDEL 已空拒绝（IOT-1015）")
    void dispatchConsumesChallengeExactlyOnce() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload)
                .thenReturn(null);

        dispatch(challengeId);

        assertThatThrownBy(() -> dispatch(challengeId))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_CONFIRM_INVALID));
    }

    @Test
    @DisplayName("凭证载荷防换设备：下发目标与签发载荷不一致拒绝（IOT-1015）")
    void dispatchRejectsChallengeForAnotherTarget() {
        String challengeId = signedChallenge();
        // 篡改载荷设备号：模拟凭证签发后请求目标被改写的攻击面
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload.replace(DEVICE_ID, "dev-999"));

        assertThatThrownBy(() -> dispatch(challengeId))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_CONFIRM_INVALID));
    }

    // ---------------------------------------------------------------- 步骤①②门槛复检

    @Test
    @DisplayName("白名单复检默认拒：签发后白名单被关闭的下发拒绝（IOT-1014），不落行")
    void dispatchRechecksWhitelistDefaultDeny() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        // 复检时白名单已关闭：allowed 翻 false（重布桩覆盖签发期取值——下发侧复检拿到的即关闭行）
        when(commandMapper.selectOne(any())).thenReturn(whitelistRow(CommandSafetyLevel.SAFETY, false));

        assertThatThrownBy(() -> dispatch(challengeId))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
        verify(commandLogMapper, never()).insert(any(IotCommandLogEntity.class));
    }

    @Test
    @DisplayName("在线预检未知态拒绝：签发时 ONLINE 但 120s 凭证窗口内快照失效（缺失/未知）拒下发（IOT-1014），不落行不外呼")
    void dispatchRejectsUnknownSnapshotStateAfterChallengeWindow() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        // 签发期 ONLINE 前提在凭证窗口内失效：TTL 过期/损坏/值域外快照在读取侧统一降级为 null
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn(null);

        assertThatThrownBy(() -> dispatch(challengeId))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.COMMAND_NOT_ALLOWED));
        // 未知态拒绝必须发生在落行与注册中心外呼之前（签发侧仅 ONLINE 的唯一下发侧防线）
        verify(commandLogMapper, never()).insert(any(IotCommandLogEntity.class));
        verify(registry, never()).sendCommand(any(), any(), any());
    }

    // ---------------------------------------------------------------- 步骤④⑤：同步下发与终态回推

    @Test
    @DisplayName("在线同步成功：落 ISSUED 行→回执置 SUCCESS 终态并发布 iot.command.completed（载荷逐字段）")
    void dispatchSyncCompletesWithReceiptAndPublishesEvent() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        when(registry.sendCommand(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)))
                .thenReturn(new CommandRef("sim-cmd-1", "SUCCESS"));

        CommandLogVO vo = dispatcher.dispatch(
                new IssueCommandRequest(challengeId, DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)), OPERATOR, TRACE_ID);

        // 落行断言：ISSUED 初态 + SYNC 通道 + 凭证引用 + 全要素留痕
        verify(commandLogMapper).insert(rowCaptor.capture());
        IotCommandLogEntity row = rowCaptor.getValue();
        assertThat(row.getCommandNo()).isEqualTo(COMMAND_NO);
        assertThat(row.getConfirmRef()).isEqualTo(challengeId);
        assertThat(row.getDeliverMode()).isEqualTo(CommandDeliverMode.SYNC);
        assertThat(row.getTraceId()).isEqualTo(TRACE_ID);
        // 终态断言：CAS 显式 deleted=0 由 SQL 承载（IT 验证），此处断言参数与事件
        verify(commandLogMapper)
                .casTerminal(eq(COMMAND_NO), eq(CommandStatus.SUCCESS.getCode()), any(), eq(null), eq(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(IotMessagingConstants.EVENT_COMMAND_COMPLETED);
        CommandCompletedPayload payload =
                (CommandCompletedPayload) eventCaptor.getValue().payload();
        assertThat(payload.commandNo()).isEqualTo(COMMAND_NO);
        assertThat(payload.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(payload.commandName()).isEqualTo(COMMAND_NAME);
        assertThat(payload.status()).isEqualTo(CommandStatus.SUCCESS.getCode());
        assertThat(payload.operator()).isEqualTo(OPERATOR);
        assertThat(payload.errorMsg()).isNull();
        assertThat(vo.status()).isEqualTo(CommandStatus.SUCCESS);
    }

    @Test
    @DisplayName("同步等待超时：默认 30s（用例 80ms）置 TIMEOUT 终态并发布事件，不抛错")
    void dispatchSyncTimesOutToTimeoutTerminal() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        doAnswer(invocation -> {
                    // 桩注册中心阻塞超过 syncTimeout：模拟设备不回执
                    RECEIPT_GATE.await(3, TimeUnit.SECONDS);
                    return new CommandRef("sim-cmd-1", "SUCCESS");
                })
                .when(registry)
                .sendCommand(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3));
        try {
            CommandLogVO vo = dispatcher.dispatch(
                    new IssueCommandRequest(challengeId, DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)),
                    OPERATOR,
                    TRACE_ID);

            verify(commandLogMapper)
                    .casTerminal(eq(COMMAND_NO), eq(CommandStatus.TIMEOUT.getCode()), any(), any(), eq(OPERATOR));
            verify(events).publishEvent(eventCaptor.capture());
            CommandCompletedPayload payload =
                    (CommandCompletedPayload) eventCaptor.getValue().payload();
            assertThat(payload.status()).isEqualTo(CommandStatus.TIMEOUT.getCode());
            assertThat(payload.errorMsg()).isNotBlank();
            assertThat(vo.status()).isEqualTo(CommandStatus.TIMEOUT);
        } finally {
            // 放行阻塞桩线程（守护池线程收尾）
            RECEIPT_GATE.countDown();
        }
    }

    @Test
    @DisplayName("同步注册中心异常：置 FAILED 终态并发布事件，向外转 IOT-1022（503）")
    void dispatchSyncRegistryFailureMarksFailedAndThrowsUnavailable() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        when(registry.sendCommand(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)))
                .thenThrow(new RegistryException("IoTDA 命令下发失败：设备离线"));

        assertThatThrownBy(() -> dispatcher.dispatch(
                        new IssueCommandRequest(challengeId, DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)),
                        OPERATOR,
                        TRACE_ID))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE);
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(commandLogMapper)
                .casTerminal(eq(COMMAND_NO), eq(CommandStatus.FAILED.getCode()), any(), any(), eq(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        CommandCompletedPayload payload =
                (CommandCompletedPayload) eventCaptor.getValue().payload();
        assertThat(payload.status()).isEqualTo(CommandStatus.FAILED.getCode());
        assertThat(payload.errorMsg()).isNotBlank();
    }

    // ---------------------------------------------------------------- 步骤④：离线异步（置 ISSUED 待结果帧）

    @Test
    @DisplayName("离线走异步：快照 OFFLINE 置 ASYNC+ISSUED 待结果帧，不等待不发布事件")
    void dispatchOfflineGoesAsyncAndStaysIssued() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{\"deviceId\":\"dev-001\",\"status\":\"OFFLINE\"}");
        when(registry.sendCommand(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)))
                .thenReturn(new CommandRef("sim-cmd-2", null));

        CommandLogVO vo = dispatcher.dispatch(
                new IssueCommandRequest(challengeId, DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)), OPERATOR, TRACE_ID);

        verify(commandLogMapper).insert(rowCaptor.capture());
        assertThat(rowCaptor.getValue().getDeliverMode()).isEqualTo(CommandDeliverMode.ASYNC);
        verify(commandLogMapper, never()).casTerminal(any(), any(), any(), any(), any());
        verifyNoInteractions(events);
        assertThat(vo.status()).isEqualTo(CommandStatus.ISSUED);
    }

    @Test
    @DisplayName("异步受理失败：注册中心拒绝置 FAILED 并转 IOT-1022")
    void dispatchAsyncAcceptanceFailureMarksFailed() {
        String challengeId = signedChallenge();
        when(valueOperations.getAndDelete("fy:iot:cmd:challenge:" + challengeId))
                .thenReturn(signedPayload);
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{\"deviceId\":\"dev-001\",\"status\":\"OFFLINE\"}");
        when(registry.sendCommand(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)))
                .thenThrow(new RegistryException("IoTDA 命令下发失败：云端拒绝"));

        assertThatThrownBy(() -> dispatcher.dispatch(
                        new IssueCommandRequest(challengeId, DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)),
                        OPERATOR,
                        TRACE_ID))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.REGISTRY_UNAVAILABLE));
        verify(commandLogMapper)
                .casTerminal(eq(COMMAND_NO), eq(CommandStatus.FAILED.getCode()), any(), any(), eq(OPERATOR));
    }

    // ---------------------------------------------------------------- 步骤⑤：结果回推帧消费

    @Test
    @DisplayName("结果回推 DELIVERED 帧：置 DELIVERED 中间态，不发布终态事件")
    void resultFrameDeliveredMarksIntermediateState() {
        when(commandLogMapper.selectOutstandingAsync(DEVICE_ID)).thenReturn(asyncRow(CommandStatus.ISSUED));

        dispatcher.completeFromResultFrame(frame("DELIVERED", null));

        verify(commandLogMapper).casDelivered(COMMAND_NO);
        verifyNoInteractions(events);
        verify(commandLogMapper, never()).casTerminal(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("结果回推 SUCCESS 帧：CAS 终态并发布 iot.command.completed（result_at=帧时点）")
    void resultFrameSuccessCompletesAndPublishes() {
        when(commandLogMapper.selectOutstandingAsync(DEVICE_ID)).thenReturn(asyncRow(CommandStatus.ISSUED));

        dispatcher.completeFromResultFrame(frame("SUCCESS", null));

        verify(commandLogMapper)
                .casTerminal(eq(COMMAND_NO), eq(CommandStatus.SUCCESS.getCode()), any(), eq(null), eq(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        CommandCompletedPayload payload =
                (CommandCompletedPayload) eventCaptor.getValue().payload();
        assertThat(payload.commandNo()).isEqualTo(COMMAND_NO);
        assertThat(payload.status()).isEqualTo(CommandStatus.SUCCESS.getCode());
        assertThat(payload.operator()).isEqualTo(OPERATOR);
        assertThat(payload.completedAt()).isEqualTo(Instant.parse("2026-09-26T01:02:03Z"));
    }

    @Test
    @DisplayName("结果回推失败帧：EXPIRED 归 TIMEOUT、失败摘要入 error_msg 与事件")
    void resultFrameExpiredMapsToTimeoutWithSummary() {
        when(commandLogMapper.selectOutstandingAsync(DEVICE_ID)).thenReturn(asyncRow(CommandStatus.ISSUED));

        dispatcher.completeFromResultFrame(frame("EXPIRED", "命令缓存过期未送达"));

        verify(commandLogMapper)
                .casTerminal(eq(COMMAND_NO), eq(CommandStatus.TIMEOUT.getCode()), any(), eq("命令缓存过期未送达"), eq(OPERATOR));
        verify(events).publishEvent(eventCaptor.capture());
        CommandCompletedPayload payload =
                (CommandCompletedPayload) eventCaptor.getValue().payload();
        assertThat(payload.status()).isEqualTo(CommandStatus.TIMEOUT.getCode());
        assertThat(payload.errorMsg()).isEqualTo("命令缓存过期未送达");
    }

    @Test
    @DisplayName("结果回推未知命令：设备无未终态异步行时容错跳过（不落库不发布）")
    void resultFrameForUnknownCommandIsSkipped() {
        when(commandLogMapper.selectOutstandingAsync(DEVICE_ID)).thenReturn(null);

        dispatcher.completeFromResultFrame(frame("SUCCESS", null));

        verifyNoInteractions(events);
        verify(commandLogMapper, never()).casTerminal(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 夹具与桩件

    /** 签发凭证并捕获载荷（下发用例回灌真实签发产物，避免与载荷格式耦合） */
    private String signedChallenge() {
        when(deviceMapper.selectOne(any())).thenReturn(deviceRow());
        when(commandMapper.selectOne(any())).thenReturn(whitelistRow(CommandSafetyLevel.SAFETY, true));
        when(valueOperations.get(SNAPSHOT_KEY)).thenReturn("{\"deviceId\":\"dev-001\",\"status\":\"ONLINE\"}");
        ConfirmChallengeVO vo =
                dispatcher.issueChallenge(new ConfirmChallengeRequest(DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)));
        verify(valueOperations)
                .set(eq("fy:iot:cmd:challenge:" + vo.challengeId()), payloadCaptor.capture(), any(Duration.class));
        signedPayload = payloadCaptor.getValue();
        return vo.challengeId();
    }

    private void dispatch(String challengeId) {
        // GETDEL 桩由各用例自行布点（一次性链/篡改载荷/正常载荷各不相同，helper 不覆盖）
        dispatcher.dispatch(
                new IssueCommandRequest(challengeId, DEVICE_ID, COMMAND_NAME, Map.of("mode", 3)), OPERATOR, TRACE_ID);
    }

    private static IotDeviceEntity deviceRow() {
        IotDeviceEntity device = new IotDeviceEntity();
        device.setDeviceId(DEVICE_ID);
        device.setProductId("prod-001");
        device.setWardId(1001L);
        device.setStatus(DeviceStatus.ONLINE);
        return device;
    }

    private static IotProductCommandEntity whitelistRow(CommandSafetyLevel safetyLevel, boolean allowed) {
        IotProductCommandEntity row = new IotProductCommandEntity();
        row.setProductId("prod-001");
        row.setCommandName(COMMAND_NAME);
        row.setSafetyLevel(safetyLevel);
        row.setAllowed(allowed);
        return row;
    }

    private static IotCommandLogEntity asyncRow(CommandStatus status) {
        IotCommandLogEntity entity = new IotCommandLogEntity();
        entity.setId(1L);
        entity.setCommandNo(COMMAND_NO);
        entity.setDeviceId(DEVICE_ID);
        entity.setCommandName(COMMAND_NAME);
        entity.setSafetyLevel(CommandSafetyLevel.SAFETY);
        entity.setOperator(OPERATOR);
        entity.setDeliverMode(CommandDeliverMode.ASYNC);
        entity.setStatus(status);
        return entity;
    }

    private static CommandResultFrame frame(String registryStatus, String result) {
        return new CommandResultFrame(
                DEVICE_ID, "sim-cmd-2", registryStatus, result, Instant.parse("2026-09-26T01:02:03Z"));
    }
}
