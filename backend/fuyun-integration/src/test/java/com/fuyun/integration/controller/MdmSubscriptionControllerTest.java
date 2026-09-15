package com.fuyun.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.common.web.PageResult;
import com.fuyun.integration.constants.MdmConstants;
import com.fuyun.integration.dto.MdmSubscriptionCreateRequest;
import com.fuyun.integration.dto.MdmSubscriptionQuery;
import com.fuyun.integration.service.IMdmSubscriptionService;
import com.fuyun.integration.vo.MdmSubscriptionVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * 主数据订阅端点薄层单测：参数装配、请求透传与 204 注销编排（业务逻辑归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class MdmSubscriptionControllerTest {

    @Mock
    private IMdmSubscriptionService mdmSubscriptionService;

    @Captor
    private ArgumentCaptor<MdmSubscriptionQuery> queryCaptor;

    private MdmSubscriptionController controller;

    @BeforeEach
    void setUp() {
        controller = new MdmSubscriptionController(mdmSubscriptionService);
    }

    @Test
    @DisplayName("矩阵查询端点：主题/订阅方/分页参数装配为查询对象，服务出参直返")
    void listDelegatesQueryParameters() {
        PageResult<MdmSubscriptionVO> expected = PageResult.of(List.of(), 0L, 20L, 0L);
        when(mdmSubscriptionService.query(any())).thenReturn(expected);

        assertThat(controller.list(MdmConstants.TOPIC_DICT, "patient", 0, 20)).isSameAs(expected);
        verify(mdmSubscriptionService).query(queryCaptor.capture());
        assertThat(queryCaptor.getValue().topic()).isEqualTo(MdmConstants.TOPIC_DICT);
        assertThat(queryCaptor.getValue().subscriberModule()).isEqualTo("patient");
    }

    @Test
    @DisplayName("登记端点：请求对象原样透传服务层")
    void registerDelegatesRequest() {
        MdmSubscriptionCreateRequest request =
                new MdmSubscriptionCreateRequest(MdmConstants.TOPIC_DICT, "patient", MdmConstants.SYNC_MODE_API_PULL);
        MdmSubscriptionVO expected = new MdmSubscriptionVO(
                7L,
                MdmConstants.TOPIC_DICT,
                "patient",
                MdmConstants.SYNC_MODE_API_PULL,
                null,
                null,
                null,
                MdmConstants.RECON_STATUS_PENDING);
        when(mdmSubscriptionService.register(request)).thenReturn(expected);

        assertThat(controller.register(request)).isSameAs(expected);
    }

    @Test
    @DisplayName("注销端点：路径 id 透传服务层并编排 204 无响应体")
    void unregisterReturnsNoContent() {
        assertThat(controller.unregister(9L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(mdmSubscriptionService).unregister(9L);
    }
}
