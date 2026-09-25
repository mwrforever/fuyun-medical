package com.fuyun.inpatient.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.messaging.EventEnvelope;
import com.fuyun.common.messaging.IdempotentConsumerSupport;
import com.fuyun.inpatient.service.OrderAuditService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M06 审方回执消费监听器单测：completed/rejected 两业务体的载荷解析与透传（V800 id 53/54
 * 冻结契约——target/auditNo/rejectReason/auditOperator/auditedAt）、缺 target 与缺
 * rejectReason 不合规帧抛出（死信留痕）、auditedAt 缺失/解析失败容错取信封时点。
 * 业务体为包级 handle 方法，@RabbitListener 入口仅做 consume 委托（模板三段式幂等归
 * IdempotentConsumerSupport，IT 面验证），单测直驱 handle 等价路径。
 */
@ExtendWith(MockitoExtension.class)
class PharmacyAuditReplyListenerTest {

    /** 回执审核时点（载荷 auditedAt 冻结形态 ISO-8601 UTC） */
    private static final Instant REPLY_AT = Instant.parse("2026-09-25T08:30:00Z");

    @Mock
    private IdempotentConsumerSupport consumerSupport;

    @Mock
    private OrderAuditService orderAuditService;

    /** 构造审方回执信封（producer=pharmacy，载荷 JSON 直构）。 */
    private EventEnvelope envelope(String eventType, String payloadJson) throws Exception {
        return new EventEnvelope(
                "ev-reply-1",
                Instant.now(Clock.systemUTC()),
                "pharmacy",
                eventType,
                "1",
                "trace-1",
                new ObjectMapper().readTree(payloadJson));
    }

    @Test
    @DisplayName("completed：target/auditNo/auditOperator/auditedAt 逐字段透传审方通过服务")
    void handleCompletedPassesReplyFields() throws Exception {
        new PharmacyAuditReplyListener(consumerSupport, orderAuditService)
                .handleAuditCompleted(envelope(
                        "pharmacy.medication-order.audit-completed",
                        "{\"target\":\"MO2026092500001\",\"auditNo\":\"RT1\",\"auditOperator\":\"1002\","
                                + "\"auditedAt\":\"2026-09-25T08:30:00Z\"}"));

        verify(orderAuditService).onPharmacistApproved("MO2026092500001", "RT1", "1002", REPLY_AT);
    }

    @Test
    @DisplayName("rejected：rejectReason 必附并逐字段透传审方驳回服务")
    void handleRejectedPassesReplyFieldsWithReason() throws Exception {
        new PharmacyAuditReplyListener(consumerSupport, orderAuditService)
                .handleAuditRejected(envelope(
                        "pharmacy.medication-order.audit-rejected",
                        "{\"target\":\"MO2026092500001\",\"auditNo\":\"RT1\",\"rejectReason\":\"剂量超限\","
                                + "\"auditOperator\":\"1002\",\"auditedAt\":\"2026-09-25T08:30:00Z\"}"));

        verify(orderAuditService).onPharmacistRejected("MO2026092500001", "RT1", "剂量超限", "1002", REPLY_AT);
    }

    @Test
    @DisplayName("载荷缺 target：不合规帧抛 IllegalStateException（死信留痕，服务零触达）")
    void rejectsEnvelopeWithoutTarget() throws Exception {
        PharmacyAuditReplyListener listener = new PharmacyAuditReplyListener(consumerSupport, orderAuditService);

        assertThatThrownBy(() -> listener.handleAuditCompleted(
                        envelope("pharmacy.medication-order.audit-completed", "{\"auditNo\":\"RT1\"}")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listener.handleAuditRejected(
                        envelope("pharmacy.medication-order.audit-rejected", "{\"rejectReason\":\"剂量超限\"}")))
                .isInstanceOf(IllegalStateException.class);
        verify(orderAuditService, never()).onPharmacistApproved(any(), any(), any(), any());
    }

    @Test
    @DisplayName("驳回缺 rejectReason（药师意见必附）：不合规帧抛 IllegalStateException 且服务零触达")
    void rejectsRejectionWithoutReason() throws Exception {
        assertThatThrownBy(() -> new PharmacyAuditReplyListener(consumerSupport, orderAuditService)
                        .handleAuditRejected(envelope(
                                "pharmacy.medication-order.audit-rejected",
                                "{\"target\":\"MO2026092500001\",\"auditNo\":\"RT1\",\"auditOperator\":\"1002\"}")))
                .isInstanceOf(IllegalStateException.class);
        verify(orderAuditService, never()).onPharmacistRejected(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("auditedAt 容错面：缺失与解析失败均取信封 occurredAt（回执不因时点格式异常丢失）")
    void fallsBackToEnvelopeTimeWhenAuditedAtInvalid() throws Exception {
        PharmacyAuditReplyListener listener = new PharmacyAuditReplyListener(consumerSupport, orderAuditService);
        EventEnvelope missing = envelope(
                "pharmacy.medication-order.audit-completed",
                "{\"target\":\"MO2026092500001\",\"auditNo\":\"RT1\",\"auditOperator\":\"1002\"}");
        listener.handleAuditCompleted(missing);
        verify(orderAuditService).onPharmacistApproved("MO2026092500001", "RT1", "1002", missing.occurredAt());

        EventEnvelope malformed = envelope(
                "pharmacy.medication-order.audit-completed",
                "{\"target\":\"MO2026092500002\",\"auditOperator\":\"1002\",\"auditedAt\":\"not-a-time\"}");
        listener.handleAuditCompleted(malformed);
        verify(orderAuditService)
                .onPharmacistApproved(eq("MO2026092500002"), isNull(), eq("1002"), eq(malformed.occurredAt()));
    }
}
