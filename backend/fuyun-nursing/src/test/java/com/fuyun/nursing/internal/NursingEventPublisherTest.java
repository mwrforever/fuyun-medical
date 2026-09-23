package com.fuyun.nursing.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.nursing.api.VitalSignRecordedPayload;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 护理域发布器单测（发布器范式收敛验收面，OutpatientEventPublisherTest 同款口径）：
 * AFTER_COMMIT 监听方法仅委托 common 模板——eventType/payload 原样透传、traceId 取 MDC
 * （单测无上下文为 null），除此零交互（信封组装/fy.topic 直发/回调红线全部收敛于
 * DomainEventSender，本发布器无副作用面）。
 */
class NursingEventPublisherTest {

    @Test
    @DisplayName("onNursingDomainEvent 直接委托：同引用 payload 透传模板，除此零交互")
    void onNursingDomainEventDelegatesToSenderOnly() {
        DomainEventSender sender = mock(DomainEventSender.class);
        NursingEventPublisher publisher = new NursingEventPublisher(sender);
        VitalSignRecordedPayload payload = new VitalSignRecordedPayload(
                42L, "Z2026092200001", Instant.parse("2026-09-22T08:30:00Z"), "BEDSIDE", "CONFIRMED", true);

        publisher.onNursingDomainEvent(new NursingDomainEvent("nursing.vital-sign.recorded", payload));

        // same 断言：payload 引用透传不包装不改写（api record 即出网契约本体）；
        // 无回调注册契约：发布器构造不触 RabbitTemplate（GC8 单槽位回调归 SystemEventPublisher，
        //   红线在 DomainEventSenderTest 锁定），此处 verifyNoMoreInteractions 锁委托面
        verify(sender).send(eq("nursing.vital-sign.recorded"), same(payload), any());
        verifyNoMoreInteractions(sender);
    }
}
