package com.fuyun.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.EventEnvelopeCodec;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.entity.DeadLetter;
import com.fuyun.integration.mapper.DeadLetterMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 死信监听器单元测试：验证死信统一落库与告警语义（M20 §5/§10）。
 *
 * <p>核心断言：x-death 轨迹解析（来源队列/原始路由键）、信封合规帧提取 event_id/event_type、
 * 不合规帧两列置空并标注"信封不合规"留痕、payload 原文与 SHA-256 摘要双列留痕、落库失败仅
 * error 告警不抛（AUTO 确认放弃该帧防毒丸无限循环）。mapper 与 codec 以 Mockito 模拟
 * （单元测试不起容器，端到端死信链路归 B2.3 集成测试）。
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterListenerTest {

    /** 测试事件号：合规信封的 eventId */
    private static final String EVENT_ID = "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60";

    /** 测试事件类型 */
    private static final String EVENT_TYPE = "system.dict.published";

    /** 测试消息体原文：合规信封线格式（含中文载荷，验证 UTF-8 解码留痕） */
    private static final String BODY =
            "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"" + EVENT_TYPE + "\",\"payload\":{\"key\":\"值\"}}";

    @Mock
    private DeadLetterMapper deadLetterMapper;

    @Mock
    private EventEnvelopeCodec eventEnvelopeCodec;

    @Captor
    private ArgumentCaptor<DeadLetter> deadLetterCaptor;

    private DeadLetterListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeadLetterListener(deadLetterMapper, eventEnvelopeCodec);
    }

    @Test
    @DisplayName("合规信封死信落库：提取 event_id/event_type 与来源队列，原文与 64 位十六进制摘要留痕，状态 PENDING")
    void persistsCompliantEnvelopeDeadLetter() {
        when(eventEnvelopeCodec.fromJson(BODY)).thenReturn(compliantEnvelope());

        listener.onDeadLetter(deadLetterMessage(BODY, death("q.it.system.dict.published", "rejected")));

        verify(deadLetterMapper).insert(deadLetterCaptor.capture());
        DeadLetter saved = deadLetterCaptor.getValue();
        assertThat(saved.getSourceQueue()).isEqualTo("q.it.system.dict.published");
        assertThat(saved.getRoutingKey()).isEqualTo(EVENT_TYPE);
        assertThat(saved.getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
        assertThat(saved.getPayloadBody()).isEqualTo(BODY);
        assertThat(saved.getPayloadDigest()).matches("[0-9a-f]{64}");
        assertThat(saved.getStatus()).isEqualTo(MessagingConstants.DEAD_LETTER_STATUS_PENDING);
    }

    @Test
    @DisplayName("信封不合规死信留痕：event_id/event_type 置空，fail_reason 标注信封不合规，仍执行落库")
    void persistsNonCompliantEnvelopeWithBlankIdentity() {
        // M20 红线 1：不合规信封拒收留痕——codec 校验失败即拒收，但必须落库备查不得静默丢弃
        when(eventEnvelopeCodec.fromJson(BODY)).thenThrow(new IllegalArgumentException("事件信封不合规：JSON 解析失败（非法 token）"));

        listener.onDeadLetter(deadLetterMessage(BODY, death("q.it.system.dict.published", "rejected")));

        verify(deadLetterMapper).insert(deadLetterCaptor.capture());
        DeadLetter saved = deadLetterCaptor.getValue();
        assertThat(saved.getEventId()).isNull();
        assertThat(saved.getEventType()).isNull();
        assertThat(saved.getFailReason()).contains("信封不合规");
    }

    @Test
    @DisplayName("落库失败告警不抛：insert 异常仅 error 告警，方法正常返回（AUTO 确认放弃该帧防死循环）")
    void swallowsInsertFailureWithAlertLog() {
        when(eventEnvelopeCodec.fromJson(BODY)).thenReturn(compliantEnvelope());
        when(deadLetterMapper.insert(any(DeadLetter.class)))
                .thenThrow(new DataAccessResourceFailureException("数据库连接不可用"));

        // 落库失败若重抛会把死信再投回死信队列形成毒丸无限循环；AUTO 确认放弃 + error 告警
        assertThatCode(() ->
                        listener.onDeadLetter(deadLetterMessage(BODY, death("q.it.system.dict.published", "rejected"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("重试耗尽 reason 透传：x-death reason=rejected 写入 fail_reason 供死信界面溯源")
    void propagatesDeathReasonIntoFailReason() {
        when(eventEnvelopeCodec.fromJson(BODY)).thenReturn(compliantEnvelope());

        listener.onDeadLetter(deadLetterMessage(BODY, death("q.it.system.dict.published", "rejected")));

        verify(deadLetterMapper).insert(deadLetterCaptor.capture());
        assertThat(deadLetterCaptor.getValue().getFailReason()).contains("rejected");
    }

    @Test
    @DisplayName("超长帧钳长：x-death 队列名/路由键与超长异常消息截断至列宽，留痕仍落库成功（W-6①）")
    void truncatesOverlongFieldsToColumnWidth() {
        String overlongQueue = "q." + "x".repeat(300);
        String overlongRoutingKey = "system." + "y".repeat(300);
        String overlongReason = "事件信封不合规：" + "z".repeat(2000);
        Map<String, Object> xDeath =
                Map.of("queue", overlongQueue, "reason", "rejected", "routing-keys", List.of(overlongRoutingKey));
        when(eventEnvelopeCodec.fromJson(BODY)).thenThrow(new IllegalArgumentException(overlongReason));

        listener.onDeadLetter(deadLetterMessage(BODY, xDeath));

        verify(deadLetterMapper).insert(deadLetterCaptor.capture());
        DeadLetter saved = deadLetterCaptor.getValue();
        assertThat(saved.getSourceQueue()).hasSize(MessagingConstants.DEAD_LETTER_SOURCE_QUEUE_MAX_LENGTH);
        assertThat(saved.getRoutingKey()).hasSize(MessagingConstants.DEAD_LETTER_ROUTING_KEY_MAX_LENGTH);
        assertThat(saved.getFailReason()).hasSize(MessagingConstants.FAIL_REASON_MAX_LENGTH);
        // 截断保留头部：不合规标注在前部，仍可识别违规类型（M20 红线 1 留痕语义不被截断削弱）
        assertThat(saved.getFailReason()).startsWith("事件信封不合规");
    }

    /**
     * 构造带 x-death 死信轨迹的原始 MQ 消息。
     *
     * @param body 消息体原文
     * @param xDeath 单条死信轨迹（来源队列/死因/原始路由键）
     * @return 死信监听入口承接的 raw Message
     */
    private Message deadLetterMessage(String body, Map<String, Object> xDeath) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(MessagingConstants.HEADER_X_DEATH, List.of(xDeath));
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    /**
     * 构造 x-death 首条轨迹样本。
     *
     * @param queue 来源队列
     * @param reason 死因（消费重试耗尽为 rejected）
     * @return x-death 轨迹条目
     */
    private Map<String, Object> death(String queue, String reason) {
        // routing-keys 为 RabbitMQ 注入的列表结构，死信侧取首条还原原始路由键
        return Map.<String, Object>of("queue", queue, "reason", reason, "routing-keys", List.of(EVENT_TYPE));
    }

    /**
     * 构造合规信封样本（codec 解析成功产物）。
     *
     * @return 七字段齐全的事件信封
     */
    private EventEnvelope compliantEnvelope() {
        return new EventEnvelope(
                EVENT_ID,
                Instant.parse("2026-09-09T01:02:03Z"),
                "system",
                EVENT_TYPE,
                "1",
                null,
                JsonNodeFactory.instance.objectNode());
    }
}
