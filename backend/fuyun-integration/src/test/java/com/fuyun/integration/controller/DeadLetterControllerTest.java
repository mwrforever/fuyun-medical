package com.fuyun.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.dto.DeadLetterQuery;
import com.fuyun.integration.service.IDeadLetterService;
import com.fuyun.integration.vo.DeadLetterDetailVO;
import com.fuyun.integration.vo.DeadLetterVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 死信端点单元测试（controller 编排薄层）：查询参数装配与响应直返，业务逻辑归 service 层测试。
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterControllerTest {

    @Mock
    private IDeadLetterService deadLetterService;

    @Captor
    private ArgumentCaptor<DeadLetterQuery> queryCaptor;

    private DeadLetterController controller;

    @BeforeEach
    void setUp() {
        controller = new DeadLetterController(deadLetterService);
    }

    @Test
    @DisplayName("列表端点：六个请求参数按序装配为查询对象，服务出参直返（无 envelope 包装）")
    void listDelegatesQueryParametersAsIs() {
        PageResult<DeadLetterVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(deadLetterService.query(any())).thenReturn(expected);

        PageResult<DeadLetterVO> actual =
                controller.list("PENDING", "system.dict.published", "e-1", "q.it.system.dict.published", 2, 50);

        assertThat(actual).isSameAs(expected);
        verify(deadLetterService).query(queryCaptor.capture());
        DeadLetterQuery query = queryCaptor.getValue();
        assertThat(query.status()).isEqualTo("PENDING");
        assertThat(query.eventType()).isEqualTo("system.dict.published");
        assertThat(query.eventId()).isEqualTo("e-1");
        assertThat(query.sourceQueue()).isEqualTo("q.it.system.dict.published");
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(50);
    }

    @Test
    @DisplayName("详情端点：路径 id 原样透传服务层")
    void detailDelegatesPathId() {
        DeadLetterDetailVO expected = new DeadLetterDetailVO(
                9L,
                "q.it.system.dict.published",
                "system.dict.published",
                "system.dict.published",
                "b1f0a2c3-4d5e-4f60-8a71-9c2b3d4e5f60",
                "{}",
                "d",
                "原因",
                null,
                "PENDING",
                0,
                null,
                null,
                null);
        when(deadLetterService.detail(9L)).thenReturn(expected);

        assertThat(controller.detail(9L)).isSameAs(expected);
    }
}
