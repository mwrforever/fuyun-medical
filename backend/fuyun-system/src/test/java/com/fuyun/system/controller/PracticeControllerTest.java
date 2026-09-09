package com.fuyun.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.dto.PracticeCheckRequest;
import com.fuyun.system.service.IPracticeService;
import com.fuyun.system.vo.PracticeCheckResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 执业授权校验端点单元测试（controller 编排薄层）：入参透传与响应直返，业务逻辑归 service 层测试。
 */
@ExtendWith(MockitoExtension.class)
class PracticeControllerTest {

    @Mock
    private IPracticeService practiceService;

    private PracticeController controller;

    @BeforeEach
    void setUp() {
        controller = new PracticeController(practiceService);
    }

    @Test
    @DisplayName("校验端点：请求对象原样透传服务层，服务响应直返（不做 envelope 包装）")
    void checkDelegatesRequestAndReturnsServiceResponse() {
        PracticeCheckRequest request = new PracticeCheckRequest(123L, "医师执业范围", OffsetDateTime.now());
        PracticeCheckResponse expected = new PracticeCheckResponse("123", "医师执业范围", request.checkTime(), false, "占位");
        when(practiceService.check(request)).thenReturn(expected);

        PracticeCheckResponse actual = controller.check(request);

        verify(practiceService).check(request);
        assertThat(actual).isSameAs(expected);
    }
}
