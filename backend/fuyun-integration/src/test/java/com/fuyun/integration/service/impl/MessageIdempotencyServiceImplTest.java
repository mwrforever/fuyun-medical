package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.messaging.ReceivedEventRecord;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.mapper.ReceivedEventMapper;
import com.fuyun.integration.properties.MessagingProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * received_event 幂等构件单元测试：验证两层去重语义（backend 宪法 A.5-6）、D-7 回查裁决与
 * 失败链状态机（Spec §3.2 步骤②④⑤）。
 *
 * <p>核心断言（M20 §4 + BRIEF-PR3-01 §3.2 D-7）：Redis SET NX 前置去重的键名/TTL 约定与故障降级
 * 放行；NX 失败时回查 received_event 台账且仅认 status=PROCESSED 行——已处理才跳过（D-7：消除
 * TTL 窗口误判丢消息）、无 PROCESSED 行（含仅 FAILED 行）放行重新处理；成功登记落库 PROCESSED；
 * 唯一索引冲突按既有行状态分流（FAILED 升级为 PROCESSED / 已是 PROCESSED 幂等跳过）；
 * settleFailure 失败收尾——释放前置键 + FAILED 留痕（重试再失败原子累加 retry_count），
 * 双保留语义（W-6③）：释放/留痕异常一律挂 suppressed，业务异常保持主异常；DB 真故障原样上抛
 * 交容器重试。Redis 与 mapper 以 Mockito 模拟（单元测试不起容器，端到端链路归集成测试）。
 */
@ExtendWith(MockitoExtension.class)
class MessageIdempotencyServiceImplTest {

    /** 测试事件号：信封 eventId（UUID 形态） */
    private static final String EVENT_ID = "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60";

    /** 测试消费者模块标识 */
    private static final String MODULE = "it";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ReceivedEventMapper receivedEventMapper;

    @Captor
    private ArgumentCaptor<ReceivedEvent> insertEntityCaptor;

    private MessageIdempotencyServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 装载 ReceivedEvent 表信息缓存：D-7 回查 lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReceivedEvent.class);
    }

    @BeforeEach
    void setUp() {
        // 构件按生产语义构造：TTL 与 application.yml 默认值一致（24h），仅覆盖常态重复投递窗口
        service = new MessageIdempotencyServiceImpl(
                stringRedisTemplate, receivedEventMapper, new MessagingProperties(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("首次消费抢占成功：setIfAbsent 占位返回 true，键名与 TTL 按约定写入")
    void tryAcquireReturnsTrueOnFirstDelivery() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertThat(service.tryAcquire(EVENT_ID, MODULE)).isTrue();

        // 键名 fy:integration:idempotency:<消费者模块>:<eventId>（A.5-1 冒号分层）；值为 eventId 便于残留键排查
        verify(valueOperations)
                .setIfAbsent(eq("fy:integration:idempotency:it:" + EVENT_ID), eq(EVENT_ID), eq(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("重复投递抢占拒绝：NX 失败且回查台账已有已处理行，返回 false 跳过（D-7 确认重复分支）")
    void tryAcquireRejectsRedeliveryWhenLedgerConfirmsProcessed() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        // 回查命中：received_event 已有该 (event_id, consumer_module) 已处理行 → 确认重复投递
        when(receivedEventMapper.exists(any())).thenReturn(true);

        assertThat(service.tryAcquire(EVENT_ID, MODULE)).isFalse();
        verify(receivedEventMapper).exists(any());
    }

    @Test
    @DisplayName("前置键残留放行：NX 失败但回查台账无行，warn 后返回 true 放行重新处理（D-7 消除丢消息窗口）")
    void tryAcquireAllowsReprocessingWhenLedgerHasNoRow() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 上次处理中断于业务执行前：NX 前置键残留而 received_event 无已处理行
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(receivedEventMapper.exists(any())).thenReturn(false);

        // at-least-once 语义：加速层残留不得误判丢弃，放行重新处理
        assertThat(service.tryAcquire(EVENT_ID, MODULE)).isTrue();
    }

    @Test
    @DisplayName("D-7 回查带已处理状态过滤：NX 失败且台账仅有 FAILED 行时放行重新处理（不丢消息）")
    void tryAcquireFiltersProcessedStatusOnLedgerLookup() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(receivedEventMapper.exists(any())).thenReturn(false);

        assertThat(service.tryAcquire(EVENT_ID, MODULE)).isTrue();

        ArgumentCaptor<Wrapper<ReceivedEvent>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(receivedEventMapper).exists(wrapperCaptor.capture());
        LambdaQueryWrapper<ReceivedEvent> wrapper = (LambdaQueryWrapper<ReceivedEvent>) wrapperCaptor.getValue();
        // 先渲染 SQL 片段：MP 条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs（3.5.17 实测）
        String sqlSegment = wrapper.getSqlSegment();
        // 回查条件必须含 status=PROCESSED 等值参数（FAILED 行不得被认作已处理）
        assertThat(sqlSegment).contains("status =");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(MessagingConstants.RECEIVED_STATUS_PROCESSED);
    }

    @Test
    @DisplayName("Redis 故障降级放行：setIfAbsent 抛 DataAccessException 时返回 true 不上抛（唯一索引兜底，不触达回查）")
    void tryAcquireDegradesToAllowOnRedisFailure() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // Redis 故障不得放大为消费不可用：降级放行后由 received_event 唯一索引承担最终幂等
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new DataAccessResourceFailureException("redis 连接不可用"));

        assertThatCode(() -> {
                    boolean allowed = service.tryAcquire(EVENT_ID, MODULE);
                    assertThat(allowed).isTrue();
                })
                .doesNotThrowAnyException();
        // 降级路径不触达台账回查：放行决策与 D-7 回查分支无关
        verify(receivedEventMapper, never()).exists(any());
    }

    @Test
    @DisplayName("成功登记落库：写入 PROCESSED 状态与处理时间，信封五要素完整映射到实体")
    void recordProcessedInsertsProcessedRow() {
        Instant occurredAt = Instant.parse("2026-09-09T01:02:03Z");

        service.recordProcessed(
                new ReceivedEventRecord(EVENT_ID, "system.dict.published", "system", occurredAt, MODULE));

        verify(receivedEventMapper).insert(insertEntityCaptor.capture());
        ReceivedEvent saved = insertEntityCaptor.getValue();
        assertThat(saved.getEventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(saved.getEventType()).isEqualTo("system.dict.published");
        assertThat(saved.getProducer()).isEqualTo("system");
        assertThat(saved.getOccurredAt()).isEqualTo(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        assertThat(saved.getConsumerModule()).isEqualTo(MODULE);
        assertThat(saved.getStatus()).isEqualTo(MessagingConstants.RECEIVED_STATUS_PROCESSED);
        assertThat(saved.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("并发重复投递兜底：insert 命中唯一索引抛 DuplicateKeyException 时吞为已处理不抛出")
    void recordProcessedSwallowsDuplicateKeyConflict() {
        // 已是 PROCESSED 行（并发重复投递被他所实例处理）：升级条件不命中（影响 0 行）→ 幂等跳过
        when(receivedEventMapper.insert(any(ReceivedEvent.class)))
                .thenThrow(new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uk_received_event_event_consumer\""));
        when(receivedEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatCode(() -> service.recordProcessed(sampleRecord())).doesNotThrowAnyException();
        verify(receivedEventMapper).insert(any(ReceivedEvent.class));
    }

    @Test
    @DisplayName("数据库真故障原样上抛：insert 抛非冲突 DataAccessException 交容器重试，禁止吞错")
    void recordProcessedRethrowsNonConflictDbFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("数据库连接不可用");
        when(receivedEventMapper.insert(any(ReceivedEvent.class))).thenThrow(failure);

        // 真故障必须暴露：静默吞掉会让消息被确认丢弃，违背 at-least-once 语义
        assertThatThrownBy(() -> service.recordProcessed(sampleRecord())).isSameAs(failure);
    }

    @Test
    @DisplayName("失败收尾：释放前置键 + 登记 FAILED 行（含原因与重试计数 1），本方法不抛出")
    void settleFailureReleasesKeyAndRegistersFailedRow() {
        IllegalStateException businessFailure = new IllegalStateException("业务失败：字典版本缺失");

        service.settleFailure(sampleRecord(), businessFailure);

        verify(stringRedisTemplate).delete("fy:integration:idempotency:it:" + EVENT_ID);
        verify(receivedEventMapper).insert(insertEntityCaptor.capture());
        ReceivedEvent saved = insertEntityCaptor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MessagingConstants.RECEIVED_STATUS_FAILED);
        assertThat(saved.getFailReason()).isEqualTo("业务失败：字典版本缺失");
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getProcessedAt()).isNull();
        assertThat(businessFailure.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("W-6③ 双保留：release 的 Redis 异常挂 suppressed 不遮蔽业务异常，FAILED 留痕照常写入")
    void settleFailureKeepsBusinessExceptionAsPrimaryWhenReleaseFails() {
        DataAccessResourceFailureException releaseFailure = new DataAccessResourceFailureException("redis 连接不可用");
        when(stringRedisTemplate.delete(anyString())).thenThrow(releaseFailure);
        IllegalStateException businessFailure = new IllegalStateException("业务失败");

        service.settleFailure(sampleRecord(), businessFailure);

        assertThat(businessFailure.getSuppressed()).containsExactly(releaseFailure);
        verify(receivedEventMapper).insert(any(ReceivedEvent.class));
    }

    @Test
    @DisplayName("失败留痕失败同样不遮蔽：DB 异常挂 suppressed，业务异常保持主异常")
    void settleFailureKeepsBusinessExceptionWhenFailureLedgerWriteFails() {
        DataAccessResourceFailureException ledgerFailure = new DataAccessResourceFailureException("数据库连接不可用");
        when(receivedEventMapper.insert(any(ReceivedEvent.class))).thenThrow(ledgerFailure);
        IllegalStateException businessFailure = new IllegalStateException("业务失败");

        service.settleFailure(sampleRecord(), businessFailure);

        assertThat(businessFailure.getSuppressed()).containsExactly(ledgerFailure);
    }

    @Test
    @DisplayName("重试再失败：FAILED 行冲突时原子累加 retry_count 并刷新原因（禁读-改-写）")
    void settleFailureAccumulatesRetryCountOnRepeatedFailure() {
        when(receivedEventMapper.insert(any(ReceivedEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_received_event_event_consumer"));
        // 守卫命中（行仍为 FAILED）：1 行累加成功
        when(receivedEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ArgumentCaptor<Wrapper<ReceivedEvent>> captor = ArgumentCaptor.forClass(Wrapper.class);

        service.settleFailure(sampleRecord(), new IllegalStateException("再次失败"));

        verify(receivedEventMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ReceivedEvent> wrapper = (LambdaUpdateWrapper<ReceivedEvent>) captor.getValue();
        assertThat(wrapper.getSqlSet()).contains("retry_count = retry_count + 1");
    }

    @Test
    @DisplayName("失败留痕迟到不回退：冲突行已被并发升级为 PROCESSED 时守卫 0 行命中，跳过留痕不回退状态")
    void settleFailureSkipsFailureLedgerWhenRowAlreadyProcessed() {
        when(receivedEventMapper.insert(any(ReceivedEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_received_event_event_consumer"));
        // 0 行命中 = status=FAILED 守卫不满足（行已被并发处理成功升级 PROCESSED），失败留痕迟到
        when(receivedEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        ArgumentCaptor<Wrapper<ReceivedEvent>> captor = ArgumentCaptor.forClass(Wrapper.class);

        service.settleFailure(sampleRecord(), new IllegalStateException("迟到失败"));

        verify(receivedEventMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ReceivedEvent> wrapper = (LambdaUpdateWrapper<ReceivedEvent>) captor.getValue();
        // 先渲染 SQL 片段触发条件参数惰性求值（3.5.17 实测）：WHERE 必须同时含
        // event_id / consumer_module / status=FAILED 守卫，禁 PROCESSED→FAILED 非法回退
        String sqlSegment = wrapper.getSqlSegment();
        assertThat(sqlSegment).contains("status =");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(UUID.fromString(EVENT_ID), MODULE, MessagingConstants.RECEIVED_STATUS_FAILED);
        assertThat(wrapper.getSqlSet()).contains("retry_count = retry_count + 1");
    }

    @Test
    @DisplayName("失败后重试成功：唯一索引冲突时按 status=FAILED 条件升级为 PROCESSED 并清失败原因")
    void recordProcessedUpgradesFailedRow() {
        when(receivedEventMapper.insert(any(ReceivedEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_received_event_event_consumer"));
        when(receivedEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        ArgumentCaptor<Wrapper<ReceivedEvent>> captor = ArgumentCaptor.forClass(Wrapper.class);

        service.recordProcessed(sampleRecord());

        verify(receivedEventMapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<ReceivedEvent> wrapper = (LambdaUpdateWrapper<ReceivedEvent>) captor.getValue();
        // 先渲染 SQL 片段：MP 等值条件参数在 getSqlSegment 惰性求值时才写入 paramNameValuePairs（3.5.17 实测）
        wrapper.getSqlSegment();
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(MessagingConstants.RECEIVED_STATUS_FAILED, MessagingConstants.RECEIVED_STATUS_PROCESSED);
        assertThat(wrapper.getSqlSet()).contains("fail_reason = NULL");
    }

    @Test
    @DisplayName("失败原因超列宽：留痕按 1000 字符截断后落库（W-6① 同款列宽防线）")
    void settleFailureTruncatesOverlongReason() {
        service.settleFailure(sampleRecord(), new IllegalStateException("原".repeat(3000)));

        verify(receivedEventMapper).insert(insertEntityCaptor.capture());
        assertThat(insertEntityCaptor.getValue().getFailReason()).hasSize(MessagingConstants.FAIL_REASON_MAX_LENGTH);
    }

    /**
     * 构造标准消费成功登记样本。
     *
     * @return 信封五要素登记记录
     */
    private ReceivedEventRecord sampleRecord() {
        return new ReceivedEventRecord(
                EVENT_ID, "system.dict.published", "system", Instant.parse("2026-09-09T01:02:03Z"), MODULE);
    }
}
