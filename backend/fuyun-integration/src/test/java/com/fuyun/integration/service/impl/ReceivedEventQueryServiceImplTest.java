package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.ReceivedEventQuery;
import com.fuyun.integration.entity.ReceivedEvent;
import com.fuyun.integration.mapper.ReceivedEventMapper;
import com.fuyun.integration.vo.ReceivedEventVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 消费台账查询服务单测：分页契约转换（0 基 ↔ MP 1 基）、过滤条件装配与出参映射。
 */
@ExtendWith(MockitoExtension.class)
class ReceivedEventQueryServiceImplTest {

    /** 测试事件号：与断言中的 eventId 一致 */
    private static final UUID EVENT_ID = UUID.fromString("b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60");

    @Mock
    private ReceivedEventMapper receivedEventMapper;

    private ReceivedEventQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReceivedEvent.class);
    }

    @BeforeEach
    void setUp() {
        service = new ReceivedEventQueryServiceImpl(receivedEventMapper, IntegrationConverter.INSTANCE);
    }

    @Test
    @DisplayName("分页查询：0 基请求与 0 基出参一致，过滤条件进入 wrapper，FAILED 行原样出参")
    void queryKeepsZeroBasedContractAndCarriesFailureRow() {
        ReceivedEvent row = new ReceivedEvent();
        row.setId(11L);
        row.setEventId(EVENT_ID);
        row.setEventType("system.dict.published");
        row.setProducer("system");
        row.setOccurredAt(OffsetDateTime.parse("2026-09-15T01:00:00Z"));
        row.setConsumerModule("it");
        row.setStatus("FAILED");
        row.setFailReason("业务失败：字典版本缺失");
        row.setRetryCount(3);
        row.setReceivedAt(OffsetDateTime.parse("2026-09-15T01:00:05Z"));
        when(receivedEventMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenAnswer(invocation -> {
                    IPage<ReceivedEvent> page = invocation.getArgument(0);
                    page.setRecords(List.of(row));
                    page.setTotal(1L);
                    return page;
                });

        PageResult<ReceivedEventVO> result = service.query(new ReceivedEventQuery(
                "system.dict.published",
                EVENT_ID,
                "it",
                "FAILED",
                OffsetDateTime.parse("2026-09-14T00:00:00Z"),
                OffsetDateTime.parse("2026-09-16T00:00:00Z"),
                0,
                20));

        assertThat(result.page()).isZero();
        assertThat(result.total()).isEqualTo(1L);
        ReceivedEventVO vo = result.content().get(0);
        assertThat(vo.status()).isEqualTo("FAILED");
        assertThat(vo.failReason()).isEqualTo("业务失败：字典版本缺失");
        assertThat(vo.retryCount()).isEqualTo(3);
        assertThat(vo.processedAt()).isNull();
        ArgumentCaptor<IPage<ReceivedEvent>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
        verify(receivedEventMapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
        // 契约 0 基 → MP 分页器 1 基（服务层唯一转换点）
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20L);
    }
}
