package com.fuyun.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.service.IEventPublicationQueryService;
import com.fuyun.integration.service.IReceivedEventQueryService;
import com.fuyun.integration.vo.EventPublicationVO;
import com.fuyun.integration.vo.ReceivedEventVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 治理查询端点薄层单测（合并类形态对齐既有 DictControllersTest）：请求参数装配与响应直返，
 * 业务逻辑归各查询服务单测。
 */
@ExtendWith(MockitoExtension.class)
class IntegrationQueryControllersTest {

    @Mock
    private IReceivedEventQueryService receivedEventQueryService;

    @Mock
    private IEventPublicationQueryService eventPublicationQueryService;

    @Captor
    private ArgumentCaptor<ReceivedEventQuery> queryCaptor;

    @Captor
    private ArgumentCaptor<EventPublicationQuery> publicationQueryCaptor;

    private ReceivedEventController receivedEventController;

    private EventPublicationController eventPublicationController;

    @BeforeEach
    void setUp() {
        receivedEventController = new ReceivedEventController(receivedEventQueryService);
        eventPublicationController = new EventPublicationController(eventPublicationQueryService);
    }

    @Test
    @DisplayName("消费台账端点：八个请求参数按序装配为查询对象，服务出参直返")
    void receivedEventListDelegatesQueryParameters() {
        UUID eventId = UUID.fromString("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");
        PageResult<ReceivedEventVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(receivedEventQueryService.query(any())).thenReturn(expected);

        PageResult<ReceivedEventVO> actual = receivedEventController.list(
                "system.dict.published",
                eventId,
                "it",
                "FAILED",
                OffsetDateTime.parse("2026-09-14T00:00:00Z"),
                OffsetDateTime.parse("2026-09-16T00:00:00Z"),
                1,
                50);

        assertThat(actual).isSameAs(expected);
        verify(receivedEventQueryService).query(queryCaptor.capture());
        ReceivedEventQuery query = queryCaptor.getValue();
        assertThat(query.eventType()).isEqualTo("system.dict.published");
        assertThat(query.eventId()).isEqualTo(eventId);
        assertThat(query.consumerModule()).isEqualTo("it");
        assertThat(query.status()).isEqualTo("FAILED");
        assertThat(query.receivedFrom()).isEqualTo(OffsetDateTime.parse("2026-09-14T00:00:00Z"));
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("投递台账端点：六个请求参数装配为查询对象，含 INCOMPLETE 完成态过滤")
    void eventPublicationListDelegatesQueryParameters() {
        PageResult<EventPublicationVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(eventPublicationQueryService.query(any())).thenReturn(expected);

        PageResult<EventPublicationVO> actual =
                eventPublicationController.list("com.fuyun.app.LifecycleProbeEvent", "INCOMPLETE", null, null, 0, 20);

        assertThat(actual).isSameAs(expected);
        verify(eventPublicationQueryService).query(publicationQueryCaptor.capture());
        assertThat(publicationQueryCaptor.getValue().status()).isEqualTo("INCOMPLETE");
        assertThat(publicationQueryCaptor.getValue().eventType()).isEqualTo("com.fuyun.app.LifecycleProbeEvent");
    }
}
