package com.fuyun.outpatient.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.outpatient.api.OrderCreatedPayload;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 门诊域发布器单测（发布器范式收敛验收面，BillingEventPublisherTest 同款口径）：
 * AFTER_COMMIT 监听方法仅委托 common 模板——eventType/payload 原样透传、traceId 取 MDC
 * （单测无上下文为 null），除此零交互（信封组装/fy.topic 直发/回调红线全部收敛于
 * DomainEventSender，本发布器无副作用面）。
 */
class OutpatientEventPublisherTest {

    @Test
    @DisplayName("onOutpatientDomainEvent 直接委托：同引用 payload 透传模板，除此零交互")
    void onOutpatientDomainEventDelegatesToSenderOnly() {
        DomainEventSender sender = mock(DomainEventSender.class);
        OutpatientEventPublisher publisher = new OutpatientEventPublisher(sender);
        OrderCreatedPayload payload = new OrderCreatedPayload(
                "O2026092100001", 7L, "O2026092100001", List.of(new OrderCreatedPayload.Line("EXAM001", "1")));

        publisher.onOutpatientDomainEvent(new OutpatientDomainEvent("outpatient.order.created", payload));

        // same 断言：payload 引用透传不包装不改写（api record 即出网契约本体）；
        // 无回调注册契约：发布器构造不触 RabbitTemplate（单槽位回调归 SystemEventPublisher，
        //   W-11 红线在 DomainEventSenderTest 锁定），此处 verifyNoMoreInteractions 锁委托面
        verify(sender).send(eq("outpatient.order.created"), same(payload), any());
        verifyNoMoreInteractions(sender);
    }
}
