package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.patient.dto.CardIssueRequest;
import com.fuyun.patient.service.VisitCardService;
import com.fuyun.patient.vo.CardVO;
import java.nio.charset.StandardCharsets;
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
 * 就诊卡端点薄层单测：发卡 200 委托直出与挂失 204 无体返回
 * （controller 禁业务逻辑与事务，状态机守卫归 service 单测；WRITE 审计落点由切面承载）。
 */
@ExtendWith(MockitoExtension.class)
class CardControllerTest {

    @Mock
    private VisitCardService visitCardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new CardController(visitCardService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("发卡端点：请求体校验后委托服务并直出卡出参（200）")
    void issueDelegatesAndReturnsCardView() throws Exception {
        when(visitCardService.issue(new CardIssueRequest(5L, "C-1001")))
                .thenReturn(new CardVO(55L, 5L, "C-1001", "ACTIVE", null, null));

        String body = mockMvc.perform(post("/api/v1/patient/cards/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":5,\"cardNo\":\"C-1001\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"cardNo\":\"C-1001\"").contains("\"status\":\"ACTIVE\"");
        verify(visitCardService).issue(new CardIssueRequest(5L, "C-1001"));
    }

    @Test
    @DisplayName("挂失端点：路径子路径承载卡号，204 无体返回")
    void lossReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/patient/cards/loss/C-0001")).andExpect(status().isNoContent());
        verify(visitCardService).loss("C-0001");
    }
}
