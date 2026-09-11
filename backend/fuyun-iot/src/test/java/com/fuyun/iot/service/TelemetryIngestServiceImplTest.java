package com.fuyun.iot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.messaging.StandardTelemetryMessage;
import com.fuyun.iot.entity.IotBindingEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.enums.TelemetryQuality;
import com.fuyun.iot.enums.TelemetrySource;
import com.fuyun.iot.mapper.IotBindingMapper;
import com.fuyun.iot.mapper.IotTelemetryMapper;
import com.fuyun.iot.service.impl.TelemetryIngestServiceImpl;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 遥测入库服务单元测试（BRIEF-PR4-01 §3 单测清单：快照注入正确、无绑定落 NULL、冲突忽略行数、
 * 批量 in 查询一次；B4.3 增补：落库成功后按病区分组推送摘要帧）。JaCoCo 核心包
 * com.fuyun.iot.service.impl LINE=1.00 承载测试。
 *
 * <p>mapper 与推送服务以 Mockito 模拟（真实 SQL 与 ON CONFLICT 语义、STOMP 收帧归
 * IotTelemetryPipelineIT 端到端验证）；绑定快照 lambda 条件的列名解析依赖 TableInfo
 * （容器外单测需手动初始化一次）。
 */
@ExtendWith(MockitoExtension.class)
class TelemetryIngestServiceImplTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-10T04:00:00Z");

    /** 测试病区 ID：摘要推送分组断言值 */
    private static final long WARD_A = 1001L;

    /** 第二病区 ID：多病区分组断言值 */
    private static final long WARD_B = 1002L;

    @Mock
    private IotBindingMapper bindingMapper;

    @Mock
    private IotTelemetryMapper telemetryMapper;

    @Mock
    private ITelemetryPushService pushService;

    @Captor
    private ArgumentCaptor<List<IotTelemetryEntity>> batchCaptor;

    @Captor
    private ArgumentCaptor<List<IotTelemetryEntity>> writtenCaptor;

    @Captor
    private ArgumentCaptor<Long> wardCaptor;

    private TelemetryIngestServiceImpl service;

    /** 无数据源事务模板：激活真实 Spring 事务同步语义（afterCommit 注册/触发链），供推送时序断言 */
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void initTableInfo() {
        // 绑定快照查询 lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), IotBindingEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new TelemetryIngestServiceImpl(bindingMapper, telemetryMapper, pushService);
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
    }

    @Test
    @DisplayName("有 BOUND 绑定：快照 patient_id/visit_id 冗余注入遥测行并返回实际插入行数")
    void ingestEnrichesRowsWithBoundSnapshot() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding("dev-001", 1001L, 2001L, WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(2);

        int inserted = service.ingest(
                List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72"), message("dev-001", "MDC_SPO2", "98")));

        assertThat(inserted).isEqualTo(2);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0).getPatientId()).isEqualTo(1001L);
        assertThat(batch.get(0).getVisitId()).isEqualTo(2001L);
        assertThat(batch.get(0).getOccurredAt()).isEqualTo(OffsetDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC));
        assertThat(batch.get(0).getQuality()).isEqualTo(TelemetryQuality.GOOD);
        assertThat(batch.get(0).getSource()).isEqualTo(TelemetrySource.IOTDA);
    }

    @Test
    @DisplayName("无绑定设备：patient_id/visit_id 落 NULL 且行仍入库（未关联仍入库口径）")
    void ingestWithoutBindingWritesNullPatientColumns() {
        when(bindingMapper.selectList(any())).thenReturn(List.of());
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
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding("dev-001", 1001L, 2001L, WARD_A)));
        // 两行中一行命中 (device_id, metric_code, occurred_at) 唯一键已存在 → ON CONFLICT 忽略，实际插入 1
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(List.of(
                message("dev-001", "MDC_ECG_HEART_RATE", "72"), message("dev-001", "MDC_ECG_HEART_RATE", "72")));

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("绑定快照单次批量 in 查询 + 单次批量写（拒绝 N+1 与循环内逐行插入）")
    void ingestIssuesSingleSnapshotQueryAndSingleBatchInsert() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding("dev-001", 1001L, 2001L, WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(3);

        service.ingest(List.of(
                message("dev-001", "MDC_ECG_HEART_RATE", "72"),
                message("dev-001", "MDC_SPO2", "98"),
                message("dev-unbound", "MDC_BODY_TEMP", "36.8")));

        verify(bindingMapper, times(1)).selectList(any());
        verify(telemetryMapper, times(1)).insertBatchIgnoreConflict(any());
    }

    @Test
    @DisplayName("空批次：直接返回 0 不触库（防空 IN 列表与空 VALUES 非法 SQL）")
    void emptyBatchShortCircuitsWithoutTouchingDatabase() {
        int inserted = service.ingest(List.of());

        assertThat(inserted).isZero();
        verifyNoInteractions(bindingMapper, telemetryMapper, pushService);
    }

    @Test
    @DisplayName("整批非数值：返回 0 且零触库（防空 VALUES 非法 SQL 引发事务异常与毒帧重投循环）")
    void allNonNumericBatchShortCircuitsWithoutTouchingDatabase() {
        // 两行 value 均不可数值定型：若不短路将组装出空实体批次，空 VALUES 渲染出非法 SQL
        int inserted = service.ingest(
                List.of(message("dev-001", "MDC_ECG_HEART_RATE", "N/A"), message("dev-002", "MDC_SPO2", "abc")));

        assertThat(inserted).isZero();
        verifyNoInteractions(bindingMapper, telemetryMapper, pushService);
    }

    @Test
    @DisplayName("value 不可数值定型：该行跳过不入库且批次不断（NUMERIC NOT NULL 列物理约束）")
    void nonNumericValueRowIsSkippedWithoutBlockingBatch() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding("dev-001", 1001L, 2001L, WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);

        int inserted = service.ingest(
                List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72"), message("dev-002", "MDC_SPO2", "N/A")));

        assertThat(inserted).isEqualTo(1);
        verify(telemetryMapper).insertBatchIgnoreConflict(batchCaptor.capture());
        List<IotTelemetryEntity> batch = batchCaptor.getValue();
        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).getMetricCode()).isEqualTo("MDC_ECG_HEART_RATE");
    }

    @Test
    @DisplayName("B4.3 摘要推送：落库成功后按绑定快照病区分组推送（/topic/iot/telemetry/{wardId} 语义）")
    void pushesSummaryGroupedByWardAfterBatchWrite() {
        when(bindingMapper.selectList(any()))
                .thenReturn(List.of(
                        binding("dev-a", 1001L, 2001L, WARD_A),
                        binding("dev-b", 1002L, 2002L, WARD_B),
                        // 绑定存在但 wardId 为空（档案未编病区）：该行仅落库不推送
                        binding("dev-c", 1003L, 2003L, null)));
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
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding("dev-001", 1001L, 2001L, WARD_A)));
        when(telemetryMapper.insertBatchIgnoreConflict(any())).thenReturn(1);
        doThrow(new IllegalStateException("broker 不可用")).when(pushService).pushSummary(any(), eq(WARD_A));

        int inserted = service.ingest(List.of(message("dev-001", "MDC_ECG_HEART_RATE", "72")));

        assertThat(inserted).as("推送失败不影响落库结果").isEqualTo(1);
    }

    @Test
    @DisplayName("事务内推送时序（A.4.2-7）：事务同步激活时推送注册于 afterCommit——事务内不推、提交后推送")
    void defersSummaryPushUntilAfterCommitWhenTransactionSynchronizationActive() {
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding("dev-001", 1001L, 2001L, WARD_A)));
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

    /** 构造绑定快照行（投影四列：device/患者/就诊/病区，病区供摘要推送分组） */
    private static IotBindingEntity binding(String deviceId, Long patientId, Long visitId, Long wardId) {
        IotBindingEntity binding = new IotBindingEntity();
        binding.setDeviceId(deviceId);
        binding.setStatus(BindingStatus.BOUND);
        binding.setPatientId(patientId);
        binding.setVisitId(visitId);
        binding.setWardId(wardId);
        return binding;
    }

    /** 构造标准遥测消息（quality=GOOD、source=IOTDA 与解析器缺省产物一致） */
    private static StandardTelemetryMessage message(String deviceId, String metricCode, String value) {
        return new StandardTelemetryMessage(deviceId, metricCode, value, "bpm", OCCURRED_AT, "GOOD", "IOTDA");
    }
}
