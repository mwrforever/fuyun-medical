package com.fuyun.inpatient.internal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.inpatient.api.payload.OrderCreatedItem;
import com.fuyun.inpatient.api.payload.OrderCreatedPayload;
import com.fuyun.inpatient.api.payload.VisitRegisteredPayload;
import com.fuyun.inpatient.constants.InpatientMessagingConstants;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 住院域发布器单测（发布器范式收敛验收面，NursingEventPublisherTest 同款口径）：
 * AFTER_COMMIT 监听方法仅委托 common 模板——eventType/payload 原样透传、traceId 取 MDC
 * （无上下文为 null），除此零交互（信封组装/fy.topic 直发/回调红线全部收敛于
 * DomainEventSender，本发布器无副作用面）。
 */
class InpatientEventPublisherTest {

    @AfterEach
    void cleanMdc() {
        // 用例间隔离：防止 traceId 用例的 MDC 残留污染后续用例（MDC 线程本地承载）
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("onInpatientDomainEvent 直接委托：同引用 payload 透传模板、traceId 无上下文为 null，除此零交互")
    void onInpatientDomainEventDelegatesToSenderOnly() {
        DomainEventSender sender = mock(DomainEventSender.class);
        InpatientEventPublisher publisher = new InpatientEventPublisher(sender);
        OrderCreatedPayload payload = new OrderCreatedPayload(
                "MO2026092500001",
                "I2026092500001",
                42L,
                "drug",
                "LONG",
                false,
                null,
                "qd",
                List.of(new OrderCreatedItem(1, "D0001", "氯化钠注射液", "0.9g", "袋", "IV", "1", "DRUG")));

        publisher.onInpatientDomainEvent(
                new InpatientDomainEvent(InpatientMessagingConstants.EVENT_ORDER_CREATED, payload));

        // same 断言：payload 引用透传不包装不改写（api/payload record 即出网契约本体）；
        // 无回调注册契约：发布器构造不触 RabbitTemplate（GC8 单槽位回调归 SystemEventPublisher，
        //   红线在 DomainEventSenderTest 锁定），此处 verifyNoMoreInteractions 锁委托面；
        // isNull 断言：单测无请求上下文，traceId 取 MDC 为 null（消费端允许空 traceId 语义）
        verify(sender).send(eq(InpatientMessagingConstants.EVENT_ORDER_CREATED), same(payload), isNull());
        verifyNoMoreInteractions(sender);
    }

    @Test
    @DisplayName("traceId 透传：MDC 已注入时原样传递，全链路追踪号不丢失")
    void traceIdIsTakenFromMdcAndPassedThrough() {
        DomainEventSender sender = mock(DomainEventSender.class);
        InpatientEventPublisher publisher = new InpatientEventPublisher(sender);
        VisitRegisteredPayload payload = new VisitRegisteredPayload(
                "I2026092500002", 43L, "AD2026092500002", Instant.parse("2026-09-25T08:00:00Z"), "UEBMI");
        MDC.put("traceId", "trace-anchor-001");

        publisher.onInpatientDomainEvent(
                new InpatientDomainEvent(InpatientMessagingConstants.EVENT_VISIT_REGISTERED, payload));

        verify(sender)
                .send(eq(InpatientMessagingConstants.EVENT_VISIT_REGISTERED), same(payload), eq("trace-anchor-001"));
        verifyNoMoreInteractions(sender);
    }
}
