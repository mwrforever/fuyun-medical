package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.patient.service.IHealthSummaryService;
import com.fuyun.patient.vo.HealthSummaryVO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 健康档案端点薄层单测：摘要查询 200 委托直出与新增项缺 itemName 400 校验拒绝
 * （controller 禁业务逻辑与事务，纠错留痕守卫归 service 单测；WRITE 审计落点由切面承载）。
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private IHealthSummaryService healthSummaryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(healthSummaryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("健康摘要端点：委托服务直出摘要出参（200）")
    void summaryDelegatesAndReturnsSummaryView() throws Exception {
        when(healthSummaryService.getSummary(5L))
                .thenReturn(new HealthSummaryVO(5L, "A", "RH_POSITIVE", "高血压", null, null, List.of()));

        String body = mockMvc.perform(get("/api/v1/patient/patients/5/health-summary"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"patientId\":5").contains("\"bloodType\":\"A\"");
        verify(healthSummaryService).getSummary(5L);
    }

    @Test
    @DisplayName("新增档案项端点：缺 itemName 校验失败返回 400，不触达服务")
    void addItemWithoutItemNameRejectedAs400() throws Exception {
        mockMvc.perform(post("/api/v1/patient/patients/5/health-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemType\":\"ALLERGY\",\"source\":\"MANUAL\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(healthSummaryService);
    }
}
