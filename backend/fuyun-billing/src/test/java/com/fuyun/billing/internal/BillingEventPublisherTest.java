package com.fuyun.billing.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fuyun.billing.api.FeeCreatedPayload;
import com.fuyun.common.messaging.DomainEventSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 收费域发布器单测（发布器范式收敛验收面，PatientEventPublisher 收敛测试同款口径）：
 * AFTER_COMMIT 监听方法仅委托 common 模板——eventType/payload 原样透传、traceId 取 MDC
 * （单测无上下文为 null），除此零交互（信封组装/fy.topic 直发/回调红线全部收敛于
 * DomainEventSender，本发布器无副作用面）。
 */
class BillingEventPublisherTest {

    @Test
    @DisplayName("onBillingDomainEvent 直接委托：同引用 payload 透传模板，除此零交互")
    void onBillingDomainEventDelegatesToSenderOnly() {
        DomainEventSender sender = mock(DomainEventSender.class);
        BillingEventPublisher publisher = new BillingEventPublisher(sender);
        FeeCreatedPayload payload =
                new FeeCreatedPayload(1L, "F1", 7L, "O2026091700001", 100L, "血常规", 6000L, "ORDER_LINKED", "k-1");

        publisher.onBillingDomainEvent(new BillingDomainEvent("billing.fee.created", payload));

        // same 断言：payload 引用透传不包装不改写（api record 即出网契约本体）；
        // 无回调注册契约：发布器构造不触 RabbitTemplate（单槽位回调归 SystemEventPublisher，
        //   W-11 红线在 DomainEventSenderTest 锁定），此处 verifyNoMoreInteractions 锁委托面
        verify(sender).send(eq("billing.fee.created"), same(payload), any());
        verifyNoMoreInteractions(sender);
    }
}
