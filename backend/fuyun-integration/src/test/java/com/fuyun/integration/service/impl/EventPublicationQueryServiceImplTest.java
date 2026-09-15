package com.fuyun.integration.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MessagingConstants;
import com.fuyun.integration.convert.IntegrationConverter;
import com.fuyun.integration.dto.EventPublicationQuery;
import com.fuyun.integration.entity.EventPublication;
import com.fuyun.integration.mapper.EventPublicationMapper;
import com.fuyun.integration.vo.EventPublicationVO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 投递台账查询服务单测：完成态派生（completion_date 空 ↔ INCOMPLETE）、分页契约与出参映射。
 */
@ExtendWith(MockitoExtension.class)
class EventPublicationQueryServiceImplTest {

    @Mock
    private EventPublicationMapper eventPublicationMapper;

    private EventPublicationQueryServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), EventPublication.class);
    }

    @BeforeEach
    void setUp() {
        service = new EventPublicationQueryServiceImpl(eventPublicationMapper, IntegrationConverter.INSTANCE);
    }

    @Test
    @DisplayName("未完成投递：completion_date 空的行派生 status=INCOMPLETE，序列化载荷列不出参")
    void queryDerivesIncompleteStatusWithoutPayloadColumn() {
        EventPublication incomplete = new EventPublication();
        incomplete.setId(UUID.fromString("2f3d0d6a-1c2b-4f5e-8a91-0b1c2d3e4f50"));
        incomplete.setListenerId("com.fuyun.app.internal.ProbeListener.on");
        incomplete.setEventType("com.fuyun.app.LifecycleProbeEvent");
        incomplete.setPublicationDate(OffsetDateTime.parse("2026-09-15T02:00:00Z"));
        when(eventPublicationMapper.selectPage(any(IPage.class), any(Wrapper.class)))
                .thenAnswer(invocation -> {
                    IPage<EventPublication> page = invocation.getArgument(0);
                    page.setRecords(List.of(incomplete));
                    page.setTotal(1L);
                    return page;
                });

        PageResult<EventPublicationVO> result = service.query(
                new EventPublicationQuery(null, MessagingConstants.PUBLICATION_STATUS_INCOMPLETE, null, null, 0, 20));

        assertThat(result.page()).isZero();
        EventPublicationVO vo = result.content().get(0);
        assertThat(vo.status()).isEqualTo(MessagingConstants.PUBLICATION_STATUS_INCOMPLETE);
        assertThat(vo.completionDate()).isNull();
        assertThat(vo.listenerId()).isEqualTo("com.fuyun.app.internal.ProbeListener.on");
    }
}
