package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.api.IntegrationErrorCode;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.DeadLetterCloseRequest;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 死信管理服务单测：分页契约转换、条件过滤、详情不存在语义（mapper 以 Mockito 模拟，
 * MP 表信息缓存手工装载——容器外单测的既有范式，见 EventRegistryServiceImplTest）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeadLetterServiceImplTest {

    @Mock
    private DeadLetterMapper deadLetterMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private AmqpAdmin amqpAdmin;

    private DeadLetterServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // lambda 条件解析列名依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DeadLetter.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeadLetterServiceImpl(IntegrationConverter.INSTANCE, rabbitTemplate, amqpAdmin);
        // ServiceImpl 的 baseMapper 为 protected 字段，单测经反射注入 mock（既有范式）
        ReflectionTestUtils.setField(service, "baseMapper", deadLetterMapper);
        ReflectionTestUtils.setField(service, "entityClass", DeadLetter.class);
    }

    @Test
    @DisplayName("分页查询：0 基请求转为 MP 1 基后的出参页码仍为 0 基，出参含预览与摘要")
    void queryKeepsZeroBasedPageContract() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenAnswer(invocation -> {
            IPage<DeadLetter> page = invocation.getArgument(0);
            page.setRecords(List.of(row));
            page.setTotal(1L);
            return page;
        });

        PageResult<DeadLetterVO> result =
                service.query(new DeadLetterQuery("PENDING", "system.dict.published", null, null, 0, 20));

        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).payloadPreview()).isEqualTo("{\"eventId\":\"x\"}");
        // MP 分页器以 1 基接收（契约 0 基 → 内部 1 基转换）
        verify(deadLetterMapper).selectPage(any(IPage.class), any(Wrapper.class));
    }

    @Test
    @DisplayName("详情查询：命中返回全文载荷；未命中抛 404 业务异常（INT-1001）")
    void detailReturnsFullPayloadOrThrowsNotFound() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectById(9L)).thenReturn(row);
        DeadLetterDetailVO detail = service.detail(9L);
        assertThat(detail.payloadBody()).isEqualTo("{\"eventId\":\"x\"}");
        assertThat(detail.status()).isEqualTo("PENDING");

        when(deadLetterMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(404L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
    }

    @Test
    @DisplayName("重放成功：原帧原文原路由键投 fy.topic，状态置 REPLAYED 且计数与处理人留痕")
    void replayPublishesOriginalFrameAndMarksReplayed() {
        DeadLetter row = pendingRow(9L, 0);
        when(deadLetterMapper.selectById(9L)).thenReturn(row);
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(new Properties());
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        DeadLetter replayed = pendingRow(9L, 1);
        replayed.setStatus(MessagingConstants.DEAD_LETTER_STATUS_REPLAYED);
        when(deadLetterMapper.selectById(9L)).thenReturn(row, replayed);

        DeadLetterDetailVO detail = service.replay(9L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
                .send(eq(MessagingConstants.EXCHANGE_TOPIC), eq("system.dict.published"), messageCaptor.capture());
        // 原文即信封线格式：不经常规转换器（否则会被二次序列化为 JSON 字符串）
        assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"eventId\":\"x\"}");
        assertThat(detail.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_REPLAYED);
        assertThat(detail.replayCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重放上限：replay_count 达 3 时拒绝（INT-1003），不触达投递")
    void replayRejectsWhenLimitReached() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 3));

        assertThatThrownBy(() -> service.replay(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_REPLAY_LIMIT_EXCEEDED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("来源队列不在位：拒绝重放（INT-1004），不触达投递")
    void replayRejectsWhenSourceQueueMissing() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(null);

        assertThatThrownBy(() -> service.replay(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("投递失败：状态写回待处理并累加计数后抛 INT-1005（Spec「重放失败回到待处理」）")
    void replayFallsBackToPendingWhenDeliveryFails() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(new Properties());
        doThrow(new AmqpConnectException(new RuntimeException("broker 不可达")))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class));
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        assertThatThrownBy(() -> service.replay(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_REPLAY_DELIVERY_FAILED);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        });
        // 失败路径仍写状态（PENDING 回置 + 计数累加）
        verify(deadLetterMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("关闭：必填原因写入备注列，状态置 CLOSED 且处理人/时间留痕")
    void closeWritesReasonAndTerminalStatus() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        DeadLetter closed = pendingRow(9L, 0);
        closed.setStatus(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        closed.setHandleNote("脏数据放弃");
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0), closed);

        DeadLetterDetailVO detail = service.close(9L, new DeadLetterCloseRequest("脏数据放弃"));

        assertThat(detail.status()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        assertThat(detail.handleNote()).isEqualTo("脏数据放弃");
        verify(deadLetterMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("终态守卫：已关闭死信拒绝重放（INT-1002，CLOSED 为终态不得再重放）")
    void replayRejectsClosedDeadLetter() {
        DeadLetter closed = pendingRow(9L, 1);
        closed.setStatus(MessagingConstants.DEAD_LETTER_STATUS_CLOSED);
        when(deadLetterMapper.selectById(9L)).thenReturn(closed);

        assertThatThrownBy(() -> service.replay(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("重放/关闭不存在 id：抛 404 业务异常（INT-1001，requirePending 缺行分支）")
    void replayAndCloseRejectMissingId() {
        when(deadLetterMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.replay(404L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        assertThatThrownBy(() -> service.close(404L, new DeadLetterCloseRequest("脏数据放弃")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_FOUND);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("信封不合规帧无可用路由键：拒绝重放（INT-1004，resolveRoutingKey 空值分支）")
    void replayRejectsWhenNoRoutingKeyAvailable() {
        DeadLetter noRoutingKey = pendingRow(9L, 0);
        noRoutingKey.setRoutingKey(null);
        noRoutingKey.setEventType(null);
        when(deadLetterMapper.selectById(9L)).thenReturn(noRoutingKey);

        assertThatThrownBy(() -> service.replay(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_NOT_REPLAYABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("重放 CAS 影响 0 行：并发处置抢先时抛状态冲突（INT-1002），本次投递已发出")
    void replayReportsConflictWhenCasMisses() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(amqpAdmin.getQueueProperties("q.it.system.dict.published")).thenReturn(new Properties());
        // 投递成功但状态写回未命中：同帧已被并发处置者抢先
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.replay(9L)).isInstanceOfSatisfying(BizException.class, ex -> {
            assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
            assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        });
        verify(rabbitTemplate).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("关闭 CAS 影响 0 行：并发处置抢先时抛状态冲突（INT-1002）")
    void closeReportsConflictWhenCasMisses() {
        when(deadLetterMapper.selectById(9L)).thenReturn(pendingRow(9L, 0));
        when(deadLetterMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.close(9L, new DeadLetterCloseRequest("脏数据放弃")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(IntegrationErrorCode.DEAD_LETTER_STATUS_NOT_ACTIONABLE);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    /**
     * 构造 PENDING 死信样本行。
     *
     * @param id          死信 ID
     * @param replayCount 重放计数
     * @return 死信实体
     */
    private DeadLetter pendingRow(Long id, int replayCount) {
        DeadLetter row = new DeadLetter();
        row.setId(id);
        row.setSourceQueue("q.it.system.dict.published");
        row.setRoutingKey("system.dict.published");
        row.setEventType("system.dict.published");
        row.setEventId("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        row.setPayloadBody("{\"eventId\":\"x\"}");
        row.setPayloadDigest("d".repeat(64));
        row.setFailReason("消费死信：reason=rejected");
        row.setFirstDeadAt(OffsetDateTime.parse("2026-09-15T01:02:03Z"));
        row.setStatus("PENDING");
        row.setReplayCount(replayCount);
        return row;
    }
}
