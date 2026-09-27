package com.fuyun.ward.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fuyun.ward.constants.WardMessagingConstants;
import com.fuyun.ward.vo.VitalBoardVO;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 体征看板服务单测（P2 PR-2 Task 12 Step 5，TDD 先红后绿）：anomaly 注记快照读出（SCAN 遍历 +
 * 反解析）、空注记出空清单、编排缺位注记透出。构造范式照模块单测基线（MockitoExtension）。
 */
@ExtendWith(MockitoExtension.class)
class VitalSignBoardServiceImplTest {

    /** 注记快照键夹具（deviceId 维度） */
    private static final String ANOMALY_KEY = WardMessagingConstants.VITAL_ANOMALY_KEY_PREFIX + "dev-bed-mattress-1";

    @Mock
    private StringRedisTemplate redisTemplate;

    private VitalSignBoardServiceImpl service;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new VitalSignBoardServiceImpl(redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("注记读出：SCAN 遍历注记键，快照反解析为视图（deviceId 维度全量出网）")
    void boardReadsAnomalySnapshots() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(ANOMALY_KEY);
        when(redisTemplate.scan(any())).thenReturn(cursor);
        String snapshot = objectMapper.writeValueAsString(new com.fuyun.iot.api.payload.TelemetryAnomalyPayload(
                "dev-bed-mattress-1",
                "MDC_BED_PRESENCE",
                "STREAM_GAP",
                Instant.parse("2026-09-26T06:00:00Z"),
                Instant.parse("2026-09-26T07:00:00Z")));
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.get(ANOMALY_KEY)).thenReturn(snapshot);

        VitalBoardVO vo = service.board(1001L);

        assertThat(vo.anomalies()).hasSize(1);
        assertThat(vo.anomalies().get(0).deviceId()).isEqualTo("dev-bed-mattress-1");
        assertThat(vo.anomalies().get(0).anomalyType()).isEqualTo("STREAM_GAP");
        assertThat(vo.note()).isNotBlank();
        assertThat(vo.presenceMetric()).isEqualTo(WardMessagingConstants.PRESENCE_METRIC_CODE);
    }

    @Test
    @DisplayName("空注记：无快照键时出空清单与 presence 锚（视图骨架语义）")
    void boardWithoutSnapshotsIsEmpty() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any())).thenReturn(cursor);

        VitalBoardVO vo = service.board(1001L);

        assertThat(vo.wardId()).isEqualTo(1001L);
        assertThat(vo.anomalies()).isEmpty();
    }

    @Test
    @DisplayName("脏快照容错：反解析失败跳过该键（不阻断看板，TTL 自然过期自愈）")
    void boardSkipsCorruptedSnapshot() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(ANOMALY_KEY);
        when(redisTemplate.scan(any())).thenReturn(cursor);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.get(ANOMALY_KEY)).thenReturn("not-json");

        VitalBoardVO vo = service.board(1001L);

        assertThat(vo.anomalies()).isEmpty();
    }

    @Test
    @DisplayName("快照过期竞态：键在 SCAN 后值已被 TTL 回收（get 返回 null）跳过")
    void boardSkipsSnapshotExpiredBetweenScanAndGet() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(ANOMALY_KEY);
        when(redisTemplate.scan(any())).thenReturn(cursor);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.get(ANOMALY_KEY)).thenReturn(null);

        VitalBoardVO vo = service.board(1001L);

        assertThat(vo.anomalies()).isEmpty();
    }

    @Test
    @DisplayName("载荷缺省字段：lastOccurredAt 为空的快照反解析后注记可空语义")
    void boardRendersAnomalyWithMissingLastOccurredAt() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(ANOMALY_KEY);
        when(redisTemplate.scan(any())).thenReturn(cursor);
        String snapshot = objectMapper.writeValueAsString(new com.fuyun.iot.api.payload.TelemetryAnomalyPayload(
                "dev-bed-mattress-1", "MDC_BED_PRESENCE", "STREAM_GAP", null, Instant.parse("2026-09-26T07:00:00Z")));
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.get(ANOMALY_KEY)).thenReturn(snapshot);

        VitalBoardVO vo = service.board(1001L);

        assertThat(vo.anomalies()).hasSize(1);
        assertThat(vo.anomalies().get(0).lastOccurredAt()).as("载荷缺省字段保持可空语义").isNull();
        assertThat(vo.anomalies().get(0).detectedAt()).isNotNull();
    }
}
