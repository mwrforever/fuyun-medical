package com.fuyun.ops.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.ops.service.IOpsWorkbenchService;
import com.fuyun.ops.vo.WorkbenchEventsVO;
import com.fuyun.ops.vo.WorkbenchEventsVO.Topic;
import com.fuyun.ops.vo.WorkbenchOverviewVO;
import com.fuyun.ops.vo.WorkbenchOverviewVO.Metrics;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工作台聚合端点单测（批次 2 册 2）：controller 编排面零业务逻辑校验——两 GET 端点直调
 * service 并原样回传（A.1-8 controller 禁业务逻辑的形态锚定）。
 */
class OpsWorkbenchControllerTest {

    private IOpsWorkbenchService workbenchService;

    private OpsWorkbenchController controller;

    @BeforeEach
    void setUp() {
        workbenchService = mock(IOpsWorkbenchService.class);
        controller = new OpsWorkbenchController(workbenchService);
    }

    @Test
    @DisplayName("overview 端点：直调 service 并原样回传（编排零加工）")
    void overviewDelegatesToService() {
        WorkbenchOverviewVO expected =
                new WorkbenchOverviewVO(new Metrics(1, 2, 3, 4, 5, 6), List.of(), List.of(), OffsetDateTime.now());
        when(workbenchService.overview()).thenReturn(expected);

        assertThat(controller.overview()).isSameAs(expected);
        verify(workbenchService).overview();
    }

    @Test
    @DisplayName("events 端点：直调 service 并原样回传（编排零加工）")
    void eventsDelegatesToService() {
        WorkbenchEventsVO expected = new WorkbenchEventsVO(
                List.of(new Topic("/ws/iot", "/topic/iot/device-status/{wardId}", "设备状态")),
                List.of(),
                List.of(),
                true,
                OffsetDateTime.now());
        when(workbenchService.events()).thenReturn(expected);

        assertThat(controller.events()).isSameAs(expected);
        verify(workbenchService).events();
    }
}
