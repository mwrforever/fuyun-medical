package com.fuyun.iot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.entity.IotDeviceEntity;
import com.fuyun.iot.entity.IotMetricDictEntity;
import com.fuyun.iot.entity.IotMetricMappingEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.MetricCategory;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.mapper.IotDeviceMapper;
import com.fuyun.iot.mapper.IotMetricDictMapper;
import com.fuyun.iot.mapper.IotMetricMappingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.properties.TelemetryValidationProperties;
import com.fuyun.iot.service.impl.TelemetryIngestServiceImpl;
import com.fuyun.iot.vo.BindingVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 遥测入库服务单元测试（BRIEF-PR4-01 §3 单测清单：快照注入正确、无绑定落 NULL、冲突忽略行数、
 * 批量查询去重；B4.3 增补：落库成功后按病区分组推送摘要帧；W-7 增补：非数值行 raw_value 承载
 * 入库、quality 强制 BAD、日志 received/inserted/unbound/non_numeric_bad 口径；Task 6 增补：
 * 五步校验编排——时间合理性 SUSPECT 标注、生理极限越界 BAD、映射缺失 RAW_PASSTHROUGH 直通 +
 * Redis 防刷屏告警、波形白名单通道丢弃/放行，批级查询边界钉死）。
 * JaCoCo 核心包 com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>绑定查询与推送服务以 Mockito 模拟（真实 SQL 与 ON CONFLICT 语义、STOMP 收帧归
 * IotTelemetryPipelineIT 端到端验证）；绑定查询消费绑定域 IBindingService.listActiveByDevices（接口级 mock，真实 SQL 守卫归
 * BindingServiceImplTest）——每批恰好一次批量 IN 查询（宪法 A.4.3-14）。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryIngestServiceImplTest {

    // 五步校验（Task 6）时间合理性以服务器当前时刻为偏差基准：夹具取类加载时点表达"新鲜无偏差"
    // 输入（原固定历史时点会被时间合理性步判 SUSPECT，与 GOOD 路径断言冲突）；偏差路径用例另行构造
    private static final Instant OCCURRED_AT = Instant.now();

    /** 测试病区 ID：摘要推送分组断言值 */
    private static final long WARD_A = 1001L;

    /** 第二病区 ID：多病区分组断言值 */
    private static final long WARD_B = 1002L;

    @Mock
    private IBindingService bindingService;

    @Mock
    private IotTelemetryMapper telemetryMapper;

    @Mock
    private ITelemetryPushService pushService;

    @Mock
    private IotDeviceMapper deviceMapper;

    @Mock
    private IotMetricMappingMapper metricMappingMapper;

    @Mock
    private IotMetricDictMapper metricDictMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Captor
    private ArgumentCaptor<List<IotTelemetryEntity>> batchCaptor;

    @Captor
    private ArgumentCaptor<List<IotTelemetryEntity>> writtenCaptor;

    @Captor
    private ArgumentCaptor<Long> wardCaptor;

    @Captor
    private ArgumentCaptor<List<String>> devicesCaptor;

    private TelemetryIngestServiceImpl service;

    /** 无数据源事务模板：激活真实 Spring 事务同步语义（afterCommit 注册/触发链），供推送时序断言 */
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void initTableInfo() {
        // 五步校验批查询的 lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次，
        // DeviceStatusServiceImplTest 同款兜底）；三实体覆盖档案/映射/字典三类批查询
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, IotDeviceEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotMetricMappingEntity.class);
        TableInfoHelper.initTableInfo(assistant, IotMetricDictEntity.class);
    }

    /** Logback 挂钩：捕获入库服务日志，断言 W-7 新口径摘要（received/inserted/unbound/non_numeric_bad） */
    private ListAppender<ILoggingEvent> logAppender;

    private Logger ingestLogger;

    @BeforeEach
    void setUp() {
        // 校验属性取真实 record 实例（默认阈值 300s、白名单仅含病区 A；仅病区 B 绑定的波形行必被丢弃）
        service = new TelemetryIngestServiceImpl(
                bindingService,
                telemetryMapper,
                pushService,
                deviceMapper,
                metricMappingMapper,
                metricDictMapper,
                redisTemplate,
                new TelemetryValidationProperties(Duration.ofSeconds(300), List.of(WARD_A), 1_000_000));
        // 无资源事务管理器（AbstractPlatformTransactionManager 最小实现）：仅承载真实事务同步链
        // 语义——getTransaction 激活同步、commit 触发 afterCommit 回调，无数据源即可驱动被测时序
        transactionTemplate = new TransactionTemplate(new AbstractPlatformTransactionManager() {

            @Override
            protected Object doGetTransaction() {
                // 无资源事务令牌：同步激活由父类统一完成，令牌本身无消费方
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                // 无资源 begin：无需打开任何连接资源
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // 无资源 commit：提交点即 afterCommit 同步回调触发点（被测推送时序依赖此语义）
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                // 无资源 rollback：本测试不涉及回滚路径
            }
        });
        // 挂 ListAppender 捕获入库日志（W-7 日志口径断言；逐条清理防用例间串扰）
        ingestLogger = (Logger) LoggerFactory.getLogger(TelemetryIngestServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        ingestLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ingestLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("有 BOUND 绑定：快照 patient_id/visit_id 冗余注入遥测行并返回实际插入行数")
    void ingestEnrichesRowsWithBoundSnapshot() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(2);

        int inserted = service.ingest(
                List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72"), message("dev-001", "MDC_SPO2", "98")));

        assertThat(inserted).isEqualTo(2);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).getPatientId()).isEqualTo(1001L);
        assertThat(batch.get(0).getVisitId()).isEqualTo("I2026090100001");
        assertThat(batch.get(0).getOccurredAt()).isEqualTo(OffsetDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC));
        assertThat(batch.get(0).getQuality()).isEqualTo(TelemetryQuality.GOOD);
        assertThat(batch.get(0).getSource()).isEqualTo(TelemetrySource.IOTDA);
    }

    @Test
    @DisplayName("无绑定设备：patient_id/visit_id 落 NULL 且行仍入库（未关联仍入库口径）")
    void ingestWithoutBindingWritesNullPatientColumns() {
        when(bindingService.listActiveByDevices(any())).thenReturn(List.of());
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(List.of(message("dev-unbound", "MDC_BODY_TEMP", "36.8")));

        assertThat(inserted).isEqualTo(1);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        IotTelemetryEntity entity = batchCaptor.getValue().get(0);
        assertThat(entity.getPatientId()).isNull();
        assertThat(entity.getVisitId()).isNull();
        // 无绑定快照（无 ward 归属）：仅落库不推送（简报 §1.4"无绑定快照的帧不推送"）
        verifyNoInteractions(pushService);
    }

    @Test
    @DisplayName("唯一键冲突忽略：mapper 返回实际插入行数（冲突行不计入幂等语义）")
    void ingestReturnsMapperReportedInsertCountOnConflict() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        // 两行中一行命中 (device_id, metric_code, occurred_at) 唯一键已存在 → ON CONFLICT 忽略，实际插入 1
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(List.of(
                message("dev-001", "MDC_ECG_HEART_RATE", "72"), message("dev-001", "MDC_ECG_HEART_RATE", "72")));

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("绑定快照每批恰好一次批量 IN 查询（入参为 distinct 设备集合）+ 单次批量写（A.4.3-14）")
    void ingestIssuesSingleBindingQueryForDistinctDevicesAndSingleBatchWrite() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(3);

        service.ingest(List.of(
                message("dev-001", "MDC_ECG_HEART_RATE", "72"),
                message("dev-001", "MDC_SPO2", "98"),
                message("dev-unbound", "MDC_BODY_TEMP", "36.8")));

        // 3 帧 2 设备：绑定查询恰好 1 次（批量 IN），入参 = 去重后的设备集合（同设备多帧不撑大入参）
        verify(bindingService, times(1)).listActiveByDevices(devicesCaptor.capture());
        assertThat(devicesCaptor.getValue()).containsExactlyInAnyOrder("dev-001", "dev-unbound");
        verify(telemetryMapper, times(1)).insertBatchIgnoreConflict(any());
    }

    @Test
    @DisplayName("空批次：直接返回 0 不触库（防空 IN 列表与空 VALUES 非法 SQL）")
    void emptyBatchShortCircuitsWithoutTouchingDatabase() {
        int inserted = service.ingest(List.of());

        assertThat(inserted).isZero();
        verifyNoInteractions(bindingService, telemetryMapper, pushService);
    }

    @Test
    @DisplayName("W-7 非数值标量行：rawValue 承载原文、value 落 NULL、quality 强制 BAD，整批照常入库")
    void nonNumericScalarRowIsCarriedAsRawValueWithBadQuality() {
        when(bindingService.listActiveByDevices(any())).thenReturn(List.of());
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(List.of(message("dev-001", "MDC_DEVICE_MSG", "异常")));

        assertThat(inserted).isEqualTo(1);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        IotTelemetryEntity entity = batchCaptor.getValue().get(0);
        assertThat(entity.getValue()).as("非数值行 value 必须落 NULL（禁哨兵值污染生理统计）").isNull();
        assertThat(entity.getRawValue()).as("非数值标量以原文承载").isEqualTo("异常");
        assertThat(entity.getQuality()).as("quality 强制 BAD（非数值定型标注，不阻断入库）").isEqualTo(TelemetryQuality.BAD);
    }

    @Test
    @DisplayName("W-7 对象/数组行：rawValue 以紧凑 JSON 承载（Jackson 标准输出无空格）")
    void objectAndArrayRowsAreCarriedAsCompactJson() {
        when(bindingService.listActiveByDevices(any())).thenReturn(List.of());
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(2);

        service.ingest(List.of(
                message("dev-001", "MDC_BP_PANEL", "{\"systolic\": 120, \"tags\": [1, 2]}"),
                message("dev-001", "MDC_WAVEFORM", "[0.1, 0.2, 0.3]")));

        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        // 形似对象/数组的原文经 Jackson 树规整：键值与元素间的空白折叠（紧凑 JSON 标准输出）
        assertThat(batch.get(0).getRawValue()).isEqualTo("{\"systolic\":120,\"tags\":[1,2]}");
        assertThat(batch.get(1).getRawValue()).isEqualTo("[0.1,0.2,0.3]");
        assertThat(batch.get(0).getValue()).isNull();
        assertThat(batch.get(0).getQuality()).isEqualTo(TelemetryQuality.BAD);
    }

    @Test
    @DisplayName("W-7 形似 JSON 但非法的原文：规整失败不丢行，按原文承载（quality=BAD 兜底标注）")
    void malformedShapedJsonFallsBackToRawText() {
        when(bindingService.listActiveByDevices(any())).thenReturn(List.of());
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        service.ingest(List.of(message("dev-001", "MDC_BP_PANEL", "{\"systolic\": }")));

        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        IotTelemetryEntity entity = batchCaptor.getValue().get(0);
        assertThat(entity.getRawValue()).as("非法 JSON 不规整不丢行，原文承载").isEqualTo("{\"systolic\": }");
        assertThat(entity.getValue()).isNull();
        assertThat(entity.getQuality()).isEqualTo(TelemetryQuality.BAD);
    }

    @Test
    @DisplayName("W-7 混合批：数值行 value 定型正常且不写 rawValue，非数值行 rawValue 承载，两行同批入库")
    void mixedBatchCarriesNumericValueAndNonNumericRawValueSideBySide() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(2);

        int inserted = service.ingest(
                List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72"), message("dev-001", "MDC_DEVICE_MSG", "N/A")));

        assertThat(inserted).isEqualTo(2);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).getValue()).as("数值行解析定型").isEqualTo(new BigDecimal("72"));
        assertThat(batch.get(0).getRawValue()).as("数值行不写 rawValue").isNull();
        assertThat(batch.get(0).getQuality()).isEqualTo(TelemetryQuality.GOOD);
        assertThat(batch.get(1).getValue()).isNull();
        assertThat(batch.get(1).getRawValue()).isEqualTo("N/A");
        assertThat(batch.get(1).getQuality()).isEqualTo(TelemetryQuality.BAD);
    }

    @Test
    @DisplayName("W-7 日志口径：info 摘要输出 received/inserted/unbound/non_numeric_bad 四计数（skip 口径退役）")
    void ingestLogsReceivedInsertedUnboundAndNonNumericBadCounters() {
        when(bindingService.listActiveByDevices(any())).thenReturn(List.of());
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(3);

        service.ingest(List.of(
                message("dev-unbound", "MDC_ECG_HEART_RATE", "72"),
                message("dev-unbound", "MDC_DEVICE_MSG", "异常"),
                message("dev-unbound", "MDC_WAVEFORM", "[1, 2]")));

        List<ILoggingEvent> infoEvents = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .toList();
        assertThat(infoEvents).as("批次恰一条 info 摘要").hasSize(1);
        String summary = infoEvents.get(0).getFormattedMessage();
        assertThat(summary).contains("received=3");
        assertThat(summary).contains("inserted=3");
        assertThat(summary).contains("unbound=3");
        assertThat(summary).contains("non_numeric_bad=2");
    }

    @Test
    @DisplayName("B4.3 摘要推送：落库成功后按绑定快照病区分组推送（/topic/iot/telemetry/{wardId} 语义）")
    void pushesSummaryGroupedByWardAfterBatchWrite() {
        // 单次批量查询一次带回三设备快照（dev-c 绑定存在但 wardId 为空：该行仅落库不推送）
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(
                        bindingVo("dev-a", 1001L, "I2026090100001", WARD_A),
                        bindingVo("dev-b", 1002L, "I2026090100002", WARD_B),
                        bindingVo("dev-c", 1003L, "I2026090100003", null)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(4);

        int inserted = service.ingest(List.of(
                message("dev-a", "MDC_ECG_HEART_RATE", "72"),
                message("dev-a", "MDC_SPO2", "98"),
                message("dev-b", "MDC_BODY_TEMP", "36.8"),
                message("dev-c", "MDC_BODY_TEMP", "37.1")));

        assertThat(inserted).isEqualTo(4);
        // 分组断言按 (wardId → 该组实体数) 配对，不绑定病区间推送先后（分组迭代序非契约）
        verify(pushService, times(2)).pushSummary(writtenCaptor.capture(), wardCaptor.capture());
        Map<Long, Integer> sizesByWard = new HashMap<>();
        List<Long> wards = wardCaptor.getAllValues();
        List<List<IotTelemetryEntity>> groups = writtenCaptor.getAllValues();
        for (int i = 0; i < wards.size(); i++) {
            sizesByWard.put(wards.get(i), groups.get(i).size());
        }
        assertThat(sizesByWard)
                .as("病区 A 组 2 行、病区 B 组 1 行、无病区行不入组")
                .containsEntry(WARD_A, 2)
                .containsEntry(WARD_B, 1)
                .hasSize(2);
    }

    @Test
    @DisplayName("摘要推送失败：仅告警不回滚落库批次（推送是辅助语义，返回实际插入行数）")
    void swallowsSummaryPushFailureWithoutFailingIngest() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);
        doThrow(new IllegalStateException("broker 不可用")).when(pushService).pushSummary(any(), eq(WARD_A));

        int inserted = service.ingest(List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72")));

        assertThat(inserted).as("推送失败不影响落库结果").isEqualTo(1);
    }

    @Test
    @DisplayName("事务内推送时序（A.4.2-7）：事务同步激活时推送注册于 afterCommit——事务内不推、提交后推送")
    void defersSummaryPushUntilAfterCommitWhenTransactionSynchronizationActive() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);
        // 推送时点记录器：push 记入 timeline，与 ingest 返回点 / execute（提交）返回点比对时序
        List<String> timeline = new ArrayList<>();
        doAnswer(invocation -> {
                    timeline.add("push");
                    return null;
                })
                .when(pushService)
                .pushSummary(any(), eq(WARD_A));

        // 匿名 AbstractPlatformTransactionManager（最小实现）无需数据源即激活完整事务同步链（getTransaction 即注册
        // 同步、commit 触发 afterCommit），真实覆盖"推送必须发生在事务提交之后"的 A.4.2-7 语义
        transactionTemplate.executeWithoutResult(status -> {
            service.ingest(List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72")));
            // ingest 返回时事务尚未提交：此刻推送必须尚未发生（I/O 移出事务）
            assertThat(timeline).as("事务提交前推送不得执行").isEmpty();
            timeline.add("ingest-returned");
        });
        timeline.add("commit-returned");

        assertThat(timeline)
                .as("推送必须晚于 ingest 返回（事务内）与提交点、早于 execute 返回（afterCommit 回调时序）")
                .containsExactly("ingest-returned", "push", "commit-returned");
    }

    @Test
    @DisplayName("五步之时间合理性：occurred_at 偏差超阈值标 SUSPECT 不丢弃（行仍入库且时刻原样保留）")
    void clockSkewedRowIsMarkedSuspectAndStillInserted() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);
        // 偏差 600s > 阈值 300s：设备时钟漂移场景（数据质量线索，标注不丢弃——FU-M14-05 口径）
        Instant skewed = Instant.now().minus(Duration.ofMinutes(10));

        int inserted = service.ingest(List.of(messageAt("dev-001", "MDC_ECG_HEART_RATE", "72", skewed)));

        assertThat(inserted).as("时间偏差行标注 SUSPECT 不丢弃，仍入库").isEqualTo(1);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        IotTelemetryEntity entity = batchCaptor.getValue().get(0);
        assertThat(entity.getQuality()).as("偏差 600s 超过默认阈值 300s → SUSPECT").isEqualTo(TelemetryQuality.SUSPECT);
        assertThat(entity.getValue()).as("数值行 value 正常定型").isEqualTo(new BigDecimal("72"));
        assertThat(entity.getOccurredAt())
                .as("occurred_at 原样保留（标注语义不改写数据）")
                .isEqualTo(OffsetDateTime.ofInstant(skewed, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("五步之数值粗校验：生理极限越界标 BAD 且数值保留入库（标注不丢弃供质量统计）")
    void physioOutOfRangeRowIsMarkedBadWithValueRetained() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(deviceEntity("dev-001", "prod-001")));
        when(metricMappingMapper.selectList(any()))
                .thenReturn(List.of(mappingEntity("prod-001", "bodyTemp", "MDC_BODY_TEMP")));
        when(metricDictMapper.selectList(any()))
                .thenReturn(List.of(dictEntity(
                        "MDC_BODY_TEMP", MetricCategory.VITAL_SIGN, new BigDecimal("35"), new BigDecimal("42"))));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(List.of(message("dev-001", "bodyTemp", "50")));

        assertThat(inserted).as("生理极限越界行标 BAD 不丢弃，仍入库").isEqualTo(1);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        IotTelemetryEntity entity = batchCaptor.getValue().get(0);
        assertThat(entity.getMetricCode()).as("映射命中行 metric_code 归一为 MDC 编码").isEqualTo("MDC_BODY_TEMP");
        assertThat(entity.getValue())
                .as("越界行数值保留入库（50 越上界 42，供 FU-M14-11 质量统计）")
                .isEqualTo(new BigDecimal("50"));
        assertThat(entity.getRawValue()).as("数值行不写 rawValue").isNull();
        assertThat(entity.getQuality()).isEqualTo(TelemetryQuality.BAD);
    }

    @Test
    @DisplayName("五步之术语映射：映射缺失按 RAW_PASSTHROUGH 原生属性名直通入库 + 每设备每属性一次告警（Redis TTL 1h 防刷屏）")
    void unmappedPropertyPassesThroughWithNativeCodeAndWarnsOncePerDeviceProperty() {
        when(bindingService.listActiveByDevices(any())).thenReturn(List.of());
        // Redis SET NX 门闸：首次 true 放行告警、第二次 false 拦截（1h TTL 内同键只告警一次）
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true, false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.ingest(
                List.of(message("dev-001", "vendor.custom_prop", "1"), message("dev-001", "vendor.custom_prop", "2")));

        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).getMetricCode())
                .as("缺映射不丢弃：metric_code=原生属性名直通（RAW_PASSTHROUGH，Spec 红线）")
                .isEqualTo("vendor.custom_prop");
        assertThat(batch.get(1).getMetricCode()).isEqualTo("vendor.custom_prop");
        // 防刷屏键 brief 冻结形态：fy:iot:warn:metric-missing:{deviceId}:{prop}，TTL 1h；
        // 两行同键各过一次门闸（NX 返回 true/false），放行次数由下方 warn 条数断言钉死
        verify(valueOperations, times(2)).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(valueOperations, times(2))
                .setIfAbsent(
                        eq("fy:iot:warn:metric-missing:dev-001:vendor.custom_prop"), eq("1"), eq(Duration.ofHours(1)));
        List<ILoggingEvent> warnEvents = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents).as("同设备同属性两行仅首行告警（Redis NX 门闸防刷屏）").hasSize(1);
    }

    @Test
    @DisplayName("波形白名单通道：白名单外病区波形行丢弃不落库 + warn + 计数；同批体征行与批级查询边界不受影响")
    void waveformRowOutsideWhitelistWardIsDroppedWithWarnAndCounter() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_B)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(deviceEntity("dev-001", "prod-001")));
        when(metricMappingMapper.selectList(any()))
                .thenReturn(List.of(
                        mappingEntity("prod-001", "ecgWave", "MDC_ECG_WAVEFORM_II"),
                        mappingEntity("prod-001", "heartRate", "MDC_ECG_HEART_RATE")));
        when(metricDictMapper.selectList(any()))
                .thenReturn(List.of(
                        dictEntity("MDC_ECG_WAVEFORM_II", MetricCategory.WAVEFORM, null, null),
                        dictEntity("MDC_ECG_HEART_RATE", MetricCategory.VITAL_SIGN, null, null)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        service.ingest(List.of(
                messageAt("dev-001", "ecgWave", "[0.1,0.2]", Instant.now()), message("dev-001", "heartRate", "72")));

        // 丢弃面在批量写之前：仅白名单外病区（病区 B ∉ 白名单[病区 A]）的波形行被剔除
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        assertThat(batch).as("波形行被丢弃，仅体征行入库").hasSize(1);
        assertThat(batch.get(0).getMetricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        // 批级查询钉死（宪法 A.4.3-14）：档案/映射/字典各恰好一次批量查询，禁循环单查
        verify(deviceMapper, times(1)).selectList(any());
        verify(metricMappingMapper, times(1)).selectList(any());
        verify(metricDictMapper, times(1)).selectList(any());
        List<ILoggingEvent> warnEvents = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertThat(warnEvents).as("白名单外波形丢弃留痕 warn（每批一条聚合）").hasSize(1);
    }

    @Test
    @DisplayName("波形白名单通道：白名单内病区波形行获准落库（在册指标数值定型 quality=GOOD）")
    void waveformRowInsideWhitelistWardIsInserted() {
        when(bindingService.listActiveByDevices(any()))
                .thenReturn(List.of(bindingVo("dev-001", 1001L, "I2026090100001", WARD_A)));
        when(deviceMapper.selectList(any())).thenReturn(List.of(deviceEntity("dev-001", "prod-001")));
        when(metricMappingMapper.selectList(any()))
                .thenReturn(List.of(mappingEntity("prod-001", "ecgWave", "MDC_ECG_WAVEFORM_II")));
        when(metricDictMapper.selectList(any()))
                .thenReturn(List.of(dictEntity("MDC_ECG_WAVEFORM_II", MetricCategory.WAVEFORM, null, null)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(List.of(messageAt("dev-001", "ecgWave", "12", Instant.now())));

        assertThat(inserted).as("白名单内病区（病区 A ∈ 白名单）波形行获准落库").isEqualTo(1);
        verify(metricDictMapper, times(1)).selectList(any());
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        IotTelemetryEntity entity = batchCaptor.getValue().get(0);
        assertThat(entity.getMetricCode()).isEqualTo("MDC_ECG_WAVEFORM_II");
        assertThat(entity.getValue()).isEqualTo(new BigDecimal("12"));
        assertThat(entity.getQuality()).as("数值可定型且无越界无偏差 → GOOD").isEqualTo(TelemetryQuality.GOOD);
    }

    /** 构造生效绑定视图（五元组投影：device/患者/就诊/病区，病区供摘要推送分组） */
    private static BindingVO bindingVo(String deviceId, Long patientId, String visitId, Long wardId) {
        return new BindingVO(
                null,
                deviceId,
                patientId,
                visitId,
                null,
                wardId,
                BindType.FIXED,
                BindingStatus.BOUND,
                null,
                null,
                "system",
                null,
                null);
    }

    /** 构造标准遥测消息（quality=GOOD、source=IOTDA 与解析器缺省产物一致；value 为原文承载） */
    private static StandardTelemetryMessage message(String deviceId, String metricCode, String value) {
        return messageAt(deviceId, metricCode, value, OCCURRED_AT);
    }

    /** 构造指定发生时刻的标准遥测消息（供时间合理性用例构造超阈值偏差时刻） */
    private static StandardTelemetryMessage messageAt(
            String deviceId, String metricCode, String value, Instant occurredAt) {
        return new StandardTelemetryMessage(deviceId, metricCode, value, "bpm", occurredAt, "GOOD", "IOTDA");
    }

    /** 构造设备档案实体（device_id + product_id 两列，五步之档案匹配的批查询产物） */
    private static IotDeviceEntity deviceEntity(String deviceId, String productId) {
        IotDeviceEntity entity = new IotDeviceEntity();
        entity.setDeviceId(deviceId);
        entity.setProductId(productId);
        return entity;
    }

    /** 构造物模型属性映射实体（product_id + property_name → metric_code，五步之术语映射的批查询产物） */
    private static IotMetricMappingEntity mappingEntity(String productId, String propertyName, String metricCode) {
        IotMetricMappingEntity entity = new IotMetricMappingEntity();
        entity.setProductId(productId);
        entity.setPropertyName(propertyName);
        entity.setMetricCode(metricCode);
        return entity;
    }

    /**
     * 构造 MDC 字典实体（metric_code 自然键 + 类别 + 生理极限边界；边界可空 = 单边界/无校验语义，
     * 五步之数值粗校验与波形白名单通道的判别数据源）。
     */
    private static IotMetricDictEntity dictEntity(
            String metricCode, MetricCategory category, BigDecimal physioMin, BigDecimal physioMax) {
        IotMetricDictEntity entity = new IotMetricDictEntity();
        entity.setMetricCode(metricCode);
        entity.setCategory(category);
        entity.setPhysioMin(physioMin);
        entity.setPhysioMax(physioMax);
        return entity;
    }
}
