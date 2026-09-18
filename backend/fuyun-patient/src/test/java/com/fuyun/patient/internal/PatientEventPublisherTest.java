package com.fuyun.patient.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fuyun.common.messaging.DomainEventSender;
import com.fuyun.patient.api.PatientUpdatedPayload;
import com.fuyun.patient.constants.PatientMessagingConstants;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 患者域 MQ 发布器单元测试（Task 13 交付；Task 5 范式收敛改 verify 委托——信封装配/路由三要素/
 * CorrelationData/回调红线断言下沉 common DomainEventSenderTest 承载）。
 *
 * <p>覆盖：AFTER_COMMIT 中继语义（注解元数据反射断言）；发布路径一行委托——eventType/payload/
 * MDC traceId 三要素原样转发模板 sender.send。真实 broker 触发时机归端到端集成测试。
 */
@ExtendWith(MockitoExtension.class)
class PatientEventPublisherTest {

    /** 测试追踪锚点：MDC traceId（模拟 HTTP 线程发布点，TraceIdFilter 已建立上下文） */
    private static final String TRACE_ID = "task13-publisher-trace";

    @Mock
    private DomainEventSender sender;

    private PatientEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PatientEventPublisher(sender);
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        // 模拟 TraceIdFilter 收尾清理，防线程复用串号影响后续用例
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("中继语义：监听方法必须挂 AFTER_COMMIT 事务事件监听（D-8 兜底 fallbackExecution=true）")
    void listenerAnnotationDeclaresAfterCommitWithFallback() throws Exception {
        Method listener = PatientEventPublisher.class.getMethod("onPatientDomainEvent", PatientDomainEvent.class);
        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        // D-8 裁决：无活动事务的发布点（补挂端点 controller 编排）也须出 MQ，禁静默丢失
        assertThat(annotation.fallbackExecution()).isTrue();
    }

    @Test
    @DisplayName("发布事件：一行委托模板——eventType/payload/MDC traceId 三要素原样转发 sender.send")
    void publishDelegatesToTemplateSenderWithMdcTraceId() {
        PatientUpdatedPayload payload = new PatientUpdatedPayload(42L, List.of("mobile"));

        publisher.onPatientDomainEvent(new PatientDomainEvent(PatientMessagingConstants.EVENT_UPDATED, payload));

        // 信封装配与路由断言下沉 common DomainEventSenderTest，此处仅锁发布器自身委托契约
        verify(sender).send(PatientMessagingConstants.EVENT_UPDATED, payload, TRACE_ID);
    }
}
