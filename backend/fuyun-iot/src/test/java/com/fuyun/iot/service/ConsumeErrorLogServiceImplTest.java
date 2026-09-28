package com.fuyun.iot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.AbandonConsumeErrorRequest;
import com.fuyun.iot.dto.ConsumeErrorQueryRequest;
import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import com.fuyun.iot.enums.ConsumeErrorStage;
import com.fuyun.iot.enums.ConsumeErrorStatus;
import com.fuyun.iot.internal.IotDeviceCommandListener;
import com.fuyun.iot.internal.TelemetryFrameParser.ParsedFrame;
import com.fuyun.iot.internal.alarm.AlarmEngine;
import com.fuyun.iot.mapper.IotConsumeErrorLogMapper;
import com.fuyun.iot.service.impl.ConsumeErrorLogServiceImpl;
import com.fuyun.iot.vo.ConsumeErrorVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 消费错误日志服务单元测试（BRIEF-PR4-01 §3 单测清单：摘要/截断/PENDING、落库失败吞并告警；
 * P2 PR-2 Task 10 扩处置面：重放 CAS 认领重新入解析管道五形态分派、放弃终态 CAS 与原因追加）。
 * JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>毒丸隔离优先于留痕是本服务核心契约：落库失败必须全吞不向消费循环上抛（否则毒丸帧无法
 * 被确认抛弃，IoTDA 重推无限循环）；测试载荷为无意义合成文本与公开样例帧，与任何真实报文无关。
 * 重放认领经真实 {@link TransactionTemplate} + {@link DataSourceTransactionManager}（mock
 * DataSource/Connection，AlarmEventReachabilityTest 同款最小事务基建）承载——独立提交单元语义
 * 以真实提交/回滚行为验证（round 1 Important-2 修复锚）。
 */
@ExtendWith(MockitoExtension.class)
class ConsumeErrorLogServiceImplTest {

    private static final String QUEUE_NAME = "it.iot.telemetry";

    /** 合法遥测帧样例（CF-7 公开字段形态，重放成功路径与五形态分派测试共用） */
    private static final String TELEMETRY_FRAME =
            "{\"deviceId\":\"dev-02\",\"metricCode\":\"MDC_SPO2\",\"value\":\"98.5\",\"occurredAt\":\"2026-09-10T04:00:00Z\"}";

    /** 合法状态帧样例（重放状态帧分派测试） */
    private static final String STATUS_FRAME =
            "{\"deviceId\":\"it-dev-001\",\"status\":\"OFFLINE\",\"occurredAt\":\"2026-09-10T05:30:00Z\"}";

    /** 合法设备告警帧样例（IoTDA 文档结构，重放告警帧分派测试） */
    private static final String ALARM_FRAME =
            "{\"resource\":\"device.alarm\",\"event_time_ms\":\"2026-09-26T08:00:00Z\","
                    + "\"notify_data\":{\"header\":{\"device_id\":\"dev-a1\"},"
                    + "\"body\":{\"alarm_id\":\"al-9\",\"name\":\"deviceAlarmEvent\",\"severity\":\"MAJOR\","
                    + "\"description\":\"设备自检告警\"}}}";

    /** 合法命令状态帧样例（IoTDA 文档结构，重放命令结果帧分派测试） */
    private static final String COMMAND_FRAME =
            "{\"resource\":\"device.command.status\",\"event_time_ms\":\"2026-09-26T01:02:03.456Z\","
                    + "\"notify_data\":{\"header\":{\"device_id\":\"dev-cmd-01\",\"product_id\":\"prod-01\"},"
                    + "\"body\":{\"command_id\":\"sim-cmd-2\",\"status\":\"SUCCESS\",\"result\":{\"resultCode\":0}}}}";

    /** 合法 IoTDA 推送帧样例（展开为 N 条标准遥测，重放推送帧分派测试） */
    private static final String IOTDA_PUSH_FRAME =
            "{\"resource\":\"device.property\",\"event\":\"report\",\"event_time_ms\":\"2026-09-12T17:30:41.632Z\","
                    + "\"notify_data\":{\"header\":{\"device_id\":\"push-dev-001\","
                    + "\"node_id\":\"push-dev-001\",\"product_id\":\"prod-01\"},"
                    + "\"body\":{\"services\":[{\"service_id\":\"Monitor\","
                    + "\"properties\":{\"heartRate\":78,\"spo2\":100},\"event_time\":\"20260912T173041Z\"}]}}}";

    @Mock
    private IotConsumeErrorLogMapper consumeErrorLogMapper;

    @Mock
    private ITelemetryIngestService telemetryIngestService;

    @Mock
    private IDeviceStatusService deviceStatusService;

    @Mock
    private AlarmEngine alarmEngine;

    @Mock
    private IotDeviceCommandListener commandResultListener;

    @Captor
    private ArgumentCaptor<IotConsumeErrorLogEntity> entityCaptor;

    @Captor
    private ArgumentCaptor<DeviceStatusEvent> statusEventCaptor;

    private ConsumeErrorLogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConsumeErrorLogServiceImpl(
                consumeErrorLogMapper,
                telemetryIngestService,
                deviceStatusService,
                alarmEngine,
                commandResultListener,
                newTestTransactions());
    }

    /**
     * 最小真实事务基建（AlarmEventReachabilityTest 同款形态）：真实 TransactionTemplate +
     * DataSourceTransactionManager，DataSource/Connection 为 mock 免 DB——链路内 SQL 经 mock
     * mapper，事务提交/回滚语义不依赖数据库方言。连接桩 lenient（不经事务的用例不触发连接获取，
     * 严格桩会误报 UnnecessaryStubbing）。
     */
    private static TransactionTemplate newTestTransactions() {
        try {
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.lenient().when(dataSource.getConnection()).thenReturn(Mockito.mock(Connection.class));
            return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        } catch (SQLException e) {
            throw new IllegalStateException("事务基建装配失败", e);
        }
    }

    // ---------------------------------------------------------------- 留痕写路径（P0 既有契约）

    @Test
    @DisplayName("留痕落 PENDING 行：SHA-256 摘要 64 位十六进制且与原文一致、stage 枚举化、replay_count=0")
    void recordParseFailureWritesPendingRowWithDigest() {
        String rawText = "{\"deviceId\":\"dev-01\",\"broken";

        service.recordParseFailure(QUEUE_NAME, rawText, "PARSE", "帧不是合法 JSON 报文");

        verify(consumeErrorLogMapper).insert(entityCaptor.capture());
        IotConsumeErrorLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getQueueName()).isEqualTo(QUEUE_NAME);
        assertThat(entity.getRawDigest()).isHexadecimal().hasSize(64).isEqualTo(expectedSha256Hex(rawText));
        // 短载荷原样留痕（未触发截断）
        assertThat(entity.getRawPayload()).isEqualTo(rawText);
        assertThat(entity.getErrorStage()).isEqualTo(ConsumeErrorStage.PARSE);
        assertThat(entity.getStatus()).isEqualTo(ConsumeErrorStatus.PENDING);
        assertThat(entity.getReplayCount()).isZero();
    }

    @Test
    @DisplayName("载荷超 4000 字符截断、errorMsg 超 500 字符截断（列宽防线），摘要仍取全文")
    void recordParseFailureTruncatesPayloadAndMessageToColumnLimits() {
        String oversizePayload = "x".repeat(5000);
        String oversizeMsg = "y".repeat(600);

        service.recordParseFailure(QUEUE_NAME, oversizePayload, "VALIDATE", oversizeMsg);

        verify(consumeErrorLogMapper).insert(entityCaptor.capture());
        IotConsumeErrorLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getRawPayload()).hasSize(4000);
        assertThat(entity.getErrorMsg()).hasSize(500);
        // 摘要恒对全文计算（与截断无关，篡改检测口径）
        assertThat(entity.getRawDigest()).isEqualTo(expectedSha256Hex(oversizePayload));
    }

    @Test
    @DisplayName("落库失败：异常全吞不向消费循环上抛（毒丸隔离优先于留痕契约）")
    void recordParseFailureSwallowsPersistenceFailure() {
        doThrow(new RuntimeException("db connection lost"))
                .when(consumeErrorLogMapper)
                // MP 3.5.17 BaseMapper 存在 insert(T)/insert(Collection) 重载，any() 须显式定型消歧
                .insert(any(IotConsumeErrorLogEntity.class));

        assertThatCode(() -> service.recordParseFailure(QUEUE_NAME, "payload", "PARSE", "非 JSON"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("errorMsg 为 null（可空列语义）：留痕行错误原因保持 null")
    void recordParseFailureKeepsNullErrorMessage() {
        service.recordParseFailure(QUEUE_NAME, "payload", "PARSE", null);

        verify(consumeErrorLogMapper).insert(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getErrorMsg()).isNull();
    }

    @Test
    @DisplayName("SHA-256 算法不可用（JDK 环境缺陷注入）：包装为 IllegalStateException 显性暴露")
    void unavailableDigestAlgorithmIsWrappedAsIllegalState() {
        try (MockedStatic<MessageDigest> mockedDigest = Mockito.mockStatic(MessageDigest.class)) {
            // 环境缺陷注入：理论不可达分支（JDK 内置算法缺失）须显性暴露而非静默吞
            mockedDigest
                    .when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("injected"));

            assertThatThrownBy(() -> service.recordParseFailure(QUEUE_NAME, "payload", "PARSE", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHA-256");
        }
    }

    // ---------------------------------------------------------------- 重放（重新入解析管道）

    @Test
    @DisplayName("重放推送展开帧：IoTDA 推送形态重新入批量入库管道（N 条展开消息整批入库）")
    void replayDispatchesIotdaPushFrameIntoIngestPipeline() {
        IotConsumeErrorLogEntity row = pendingRow(10L, IOTDA_PUSH_FRAME, "MSG");
        when(consumeErrorLogMapper.selectById(10L)).thenReturn(row, replayedRow(row));
        when(consumeErrorLogMapper.casMarkReplayed(eq(10L), anyString())).thenReturn(1);

        service.replay(10L);

        verify(telemetryIngestService)
                .ingest(argThat((java.util.List<StandardTelemetryMessage> batch) ->
                        batch.size() == 2 && "push-dev-001".equals(batch.get(0).deviceId())));
    }

    @Test
    @DisplayName("重放成功：CAS 认领后遥测帧重新入批量入库管道，返回处置后视图")
    void replayClaimsAndDispatchesTelemetryFrameIntoIngestPipeline() {
        IotConsumeErrorLogEntity row = pendingRow(1L, TELEMETRY_FRAME, "PARSED-MSG");
        when(consumeErrorLogMapper.selectById(1L)).thenReturn(row);
        when(consumeErrorLogMapper.casMarkReplayed(eq(1L), anyString())).thenReturn(1);
        // 处置后回读行（status=REPLAYED、replay_count 累加）
        when(consumeErrorLogMapper.selectById(1L)).thenReturn(replayedRow(row));

        ConsumeErrorVO vo = service.replay(1L);

        verify(consumeErrorLogMapper).casMarkReplayed(eq(1L), anyString());
        verify(telemetryIngestService)
                .ingest(argThat((java.util.List<StandardTelemetryMessage> batch) ->
                        batch.size() == 1 && "dev-02".equals(batch.get(0).deviceId())));
        assertThat(vo.status()).isEqualTo(ConsumeErrorStatus.REPLAYED);
        assertThat(vo.replayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重放分派：状态帧/告警帧/命令结果帧分别交状态服务/告警引擎/命令监听器")
    void replayDispatchesStatusAlarmAndCommandFramesToTheirPipelines() {
        stubReplayFlow(11L, STATUS_FRAME);
        service.replay(11L);
        verify(deviceStatusService).apply(statusEventCaptor.capture());
        assertThat(statusEventCaptor.getValue().deviceId()).isEqualTo("it-dev-001");

        stubReplayFlow(12L, ALARM_FRAME);
        service.replay(12L);
        verify(alarmEngine)
                .evaluateDeviceAlarm(argThat((ParsedFrame.DeviceAlarmFrame frame) ->
                        "dev-a1".equals(frame.deviceId()) && "deviceAlarmEvent".equals(frame.metricCode())));

        stubReplayFlow(13L, COMMAND_FRAME);
        service.replay(13L);
        verify(commandResultListener)
                .onCommandResultFrame(argThat((ParsedFrame.CommandResultFrame frame) ->
                        "sim-cmd-2".equals(frame.commandId()) && "SUCCESS".equals(frame.registryStatus())));
    }

    @Test
    @DisplayName("重放记录不存在：IOT-1020 404，不触发 CAS 与管道")
    void replayUnknownRecordRejectedWithIot1020() {
        when(consumeErrorLogMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.replay(404L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_NOT_FOUND));
        verify(consumeErrorLogMapper, never()).casMarkReplayed(any(), anyString());
    }

    @Test
    @DisplayName("已放弃记录禁止重放（终态语义）：IOT-1021 409，不触发 CAS")
    void replayAbandonedRecordRejectedWithIot1021() {
        IotConsumeErrorLogEntity row = pendingRow(5L, TELEMETRY_FRAME, "MSG");
        row.setStatus(ConsumeErrorStatus.ABANDONED);
        when(consumeErrorLogMapper.selectById(5L)).thenReturn(row);

        assertThatThrownBy(() -> service.replay(5L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED));
        verify(consumeErrorLogMapper, never()).casMarkReplayed(any(), anyString());
    }

    @Test
    @DisplayName("载荷引用缺失（载体不可读帧留痕为空引用）：无管道可入，借承 IOT-1021 拒绝")
    void replayWithoutPayloadReferenceRejectedWithIot1021() {
        IotConsumeErrorLogEntity row = pendingRow(6L, null, "MSG");
        when(consumeErrorLogMapper.selectById(6L)).thenReturn(row);

        assertThatThrownBy(() -> service.replay(6L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED))
                .hasMessageContaining("载荷引用缺失");
        verify(consumeErrorLogMapper, never()).casMarkReplayed(any(), anyString());
    }

    @Test
    @DisplayName("重放 CAS 落败（并发被抢或已放弃）：IOT-1021 409，不入解析管道")
    void replayCasFailureRejectedWithIot1021() {
        when(consumeErrorLogMapper.selectById(7L)).thenReturn(pendingRow(7L, TELEMETRY_FRAME, "MSG"));
        when(consumeErrorLogMapper.casMarkReplayed(eq(7L), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.replay(7L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED));
        verify(telemetryIngestService, never()).ingest(anyList());
    }

    @Test
    @DisplayName("重放处理失败（毒丸帧再解析仍失败）：不回滚认领，借承 IOT-1021 上抛带已标记语义")
    void replayPipelineFailureKeepsClaimAndTranslatesToIot1021() {
        when(consumeErrorLogMapper.selectById(8L)).thenReturn(pendingRow(8L, "not-a-json-frame", "MSG"));
        when(consumeErrorLogMapper.casMarkReplayed(eq(8L), anyString())).thenReturn(1);

        assertThatThrownBy(() -> service.replay(8L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED))
                .hasMessageContaining("已标记 REPLAYED");
        verify(consumeErrorLogMapper).casMarkReplayed(eq(8L), anyString());
        verify(telemetryIngestService, never()).ingest(anyList());
    }

    @Test
    @DisplayName("Important-2 锚：管道失败后认领事务仍真实提交（独立提交单元，commit 发生且零 rollback）")
    void replayPipelineFailureLeavesClaimTransactionCommitted() {
        // 真实事务基建 + 本用例私有 Connection mock：提交/回滚行为可断言（AlarmEventReachabilityTest 形态）
        try {
            Connection connection = Mockito.mock(Connection.class);
            DataSource dataSource = Mockito.mock(DataSource.class);
            Mockito.when(dataSource.getConnection()).thenReturn(connection);
            ConsumeErrorLogServiceImpl isolated = new ConsumeErrorLogServiceImpl(
                    consumeErrorLogMapper,
                    telemetryIngestService,
                    deviceStatusService,
                    alarmEngine,
                    commandResultListener,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            when(consumeErrorLogMapper.selectById(8L)).thenReturn(pendingRow(8L, "not-a-json-frame", "MSG"));
            when(consumeErrorLogMapper.casMarkReplayed(eq(8L), anyString())).thenReturn(1);

            assertThatThrownBy(() -> isolated.replay(8L))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("已标记 REPLAYED");

            // 认领写入发生在独立事务单元内且该单元已提交：管道抛出的 RuntimeException（含翻译后的
            // BizException）未触发任何回滚——「记录已标记 REPLAYED 供追溯、replay_count 已累加」
            // 契约的生产语义由此锚定（修复前方法级事务下 rollback 必被调用、commit 缺席）
            verify(consumeErrorLogMapper).casMarkReplayed(eq(8L), anyString());
            verify(connection).commit();
            verify(connection, never()).rollback();
        } catch (SQLException e) {
            throw new IllegalStateException("事务基建装配失败", e);
        }
    }

    @Test
    @DisplayName("重放入库管道业务失败（入库异常）：同样翻译 IOT-1021 且不回滚认领")
    void replayIngestFailureTranslatesToIot1021() {
        when(consumeErrorLogMapper.selectById(9L)).thenReturn(pendingRow(9L, TELEMETRY_FRAME, "MSG"));
        when(consumeErrorLogMapper.casMarkReplayed(eq(9L), anyString())).thenReturn(1);
        doThrow(new IllegalStateException("db down"))
                .when(telemetryIngestService)
                .ingest(anyList());

        assertThatThrownBy(() -> service.replay(9L))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED))
                .hasMessageContaining("db down");
        // 认领在管道失败前已独立提交（同锚语义：mock 层验证认领调用发生）
        verify(consumeErrorLogMapper).casMarkReplayed(eq(9L), anyString());
    }

    // ---------------------------------------------------------------- 放弃（终态处置）

    @Test
    @DisplayName("放弃成功：CAS 仅 PENDING 迁移 ABANDONED，原因追加承载于 error_msg")
    void abandonClaimsPendingRowAndAppendsReasonToErrorMsg() {
        IotConsumeErrorLogEntity row = pendingRow(21L, "PAYLOAD", "帧不是合法 JSON 报文");
        when(consumeErrorLogMapper.selectById(21L)).thenReturn(row, abandonedRow(row, "MERGED-MSG"));
        when(consumeErrorLogMapper.casMarkAbandoned(eq(21L), eq("帧不是合法 JSON 报文；放弃原因：重复帧已去重"), anyString()))
                .thenReturn(1);

        ConsumeErrorVO vo = service.abandon(21L, new AbandonConsumeErrorRequest("重复帧已去重"));

        verify(consumeErrorLogMapper).casMarkAbandoned(eq(21L), eq("帧不是合法 JSON 报文；放弃原因：重复帧已去重"), anyString());
        assertThat(vo.status()).isEqualTo(ConsumeErrorStatus.ABANDONED);
    }

    @Test
    @DisplayName("放弃原因空白：服务层显式拒（400 借承 IOT-1021），不触 CAS")
    void abandonBlankReasonRejectedWith400() {
        assertThatThrownBy(() -> service.abandon(1L, new AbandonConsumeErrorRequest("  ")))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED);
                    assertThat(e.getMessage()).contains("放弃原因强制");
                });
        verify(consumeErrorLogMapper, never()).casMarkAbandoned(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("放弃记录不存在：IOT-1020 404")
    void abandonUnknownRecordRejectedWithIot1020() {
        when(consumeErrorLogMapper.selectById(31L)).thenReturn(null);

        assertThatThrownBy(() -> service.abandon(31L, new AbandonConsumeErrorRequest("原因")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_NOT_FOUND));
    }

    @Test
    @DisplayName("放弃 CAS 落败（非 PENDING 或并发双弃）：IOT-1021 409")
    void abandonCasFailureRejectedWithIot1021() {
        when(consumeErrorLogMapper.selectById(32L)).thenReturn(pendingRow(32L, "PAYLOAD", "MSG"));
        when(consumeErrorLogMapper.casMarkAbandoned(eq(32L), anyString(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.abandon(32L, new AbandonConsumeErrorRequest("原因")))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(IotErrorCode.CONSUME_ERROR_STATE_NOT_ALLOWED));
    }

    @Test
    @DisplayName("放弃原因超列宽：追加后整体 500 字符截断（V401 列宽防线）")
    void abandonReasonTruncatesMergedMessageToColumnLimit() {
        IotConsumeErrorLogEntity row = pendingRow(33L, "PAYLOAD", "y".repeat(600));
        when(consumeErrorLogMapper.selectById(33L)).thenReturn(row, abandonedRow(row, null));
        when(consumeErrorLogMapper.casMarkAbandoned(eq(33L), anyString(), anyString()))
                .thenReturn(1);

        service.abandon(33L, new AbandonConsumeErrorRequest("原因"));

        verify(consumeErrorLogMapper)
                .casMarkAbandoned(eq(33L), argThat(msg -> msg != null && msg.length() == 500), anyString());
    }

    // ---------------------------------------------------------------- 分页

    @Test
    @DisplayName("分页：队列名/状态过滤透传，page/size 缺省补齐（0/20），实体经 VO 出网")
    void pageAppliesFiltersAndDefaults() {
        Page<IotConsumeErrorLogEntity> result = new Page<>(1, 20);
        result.setRecords(new java.util.ArrayList<>(java.util.List.of(pendingRow(41L, "P", "M"))));
        result.setTotal(1);
        when(consumeErrorLogMapper.selectPage(any(), any())).thenReturn(result);

        var vo = service.page(new ConsumeErrorQueryRequest(null, null, QUEUE_NAME, ConsumeErrorStatus.PENDING));

        assertThat(vo.total()).isEqualTo(1);
        assertThat(vo.content().get(0).errorId()).isEqualTo(41L);
        assertThat(vo.page()).isZero();
        assertThat(vo.size()).isEqualTo(20);
    }

    // ---------------------------------------------------------------- 夹具

    /** 构造 PENDING 留痕行（stage 固定 PARSE，载荷/原因由用例指定） */
    private static IotConsumeErrorLogEntity pendingRow(Long id, String payload, String errorMsg) {
        IotConsumeErrorLogEntity entity = new IotConsumeErrorLogEntity();
        entity.setErrorId(id);
        entity.setQueueName(QUEUE_NAME);
        entity.setRawDigest("d".repeat(64));
        entity.setRawPayload(payload);
        entity.setErrorStage(ConsumeErrorStage.PARSE);
        entity.setErrorMsg(errorMsg);
        entity.setStatus(ConsumeErrorStatus.PENDING);
        entity.setReplayCount(0);
        return entity;
    }

    /** 构造 CAS 认领后的 REPLAYED 行（replay_count 累加） */
    private static IotConsumeErrorLogEntity replayedRow(IotConsumeErrorLogEntity source) {
        IotConsumeErrorLogEntity entity = pendingRow(source.getErrorId(), source.getRawPayload(), source.getErrorMsg());
        entity.setStatus(ConsumeErrorStatus.REPLAYED);
        entity.setReplayCount(1);
        return entity;
    }

    /** 构造 CAS 迁移后的 ABANDONED 行（原因已在合并文本中，用例以 mergedMsg 断言 CAS 入参） */
    private static IotConsumeErrorLogEntity abandonedRow(IotConsumeErrorLogEntity source, String mergedMsg) {
        IotConsumeErrorLogEntity entity = pendingRow(source.getErrorId(), source.getRawPayload(), mergedMsg);
        entity.setStatus(ConsumeErrorStatus.ABANDONED);
        return entity;
    }

    /** 整备一次重放流：行装载 + CAS 认领成功 + 处置后回读（多形态分派用例复用） */
    private void stubReplayFlow(Long id, String payload) {
        IotConsumeErrorLogEntity row = pendingRow(id, payload, "MSG");
        when(consumeErrorLogMapper.selectById(id)).thenReturn(row, replayedRow(row));
        when(consumeErrorLogMapper.casMarkReplayed(eq(id), anyString())).thenReturn(1);
    }

    /** 测试侧独立计算 SHA-256 期望值（与服务实现同算法不同代码路径，防实现自证） */
    private static String expectedSha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
