package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.dto.DepositRequest;
import com.fuyun.billing.service.IDepositService;
import com.fuyun.billing.vo.DepositAccountVO;
import com.fuyun.common.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 押金端点薄层冒烟单测（JaCoCo BUNDLE 兜底）：缴存透传 200 且流水 id 出参/缺 amountFen
 * @Valid 400（服务零交互）/按就诊号查询透传 200（controller 禁业务逻辑与事务，原子记账/
 * 欠费判定/守卫归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class DepositControllerTest {

    @Mock
    private IDepositService depositService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new DepositController(depositService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("缴存端点：合法请求 200 且流水 id 出参（记账与欠费判定语义由服务单测锁定）")
    void depositPassesThroughServiceResult() throws Exception {
        when(depositService.deposit(any(DepositRequest.class))).thenReturn(9001L);

        String body = mockMvc.perform(
                        post("/api/v1/billing/deposits")
                                .contentType("application/json")
                                .content(
                                        "{\"patientId\":7,\"visitId\":\"I2026091600001\",\"amountFen\":50000,\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("9001");
        verify(depositService).deposit(any(DepositRequest.class));
    }

    @Test
    @DisplayName("缴存端点：缺 amountFen 字段 @Valid 400 且服务零交互（controller 禁业务逻辑）")
    void depositRejectsMissingAmountAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/billing/deposits")
                        .contentType("application/json")
                        .content("{\"patientId\":7,\"visitId\":\"I2026091600001\",\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(depositService);
    }

    @Test
    @DisplayName("账户查询端点：visitId 查询参数 200 透传 VO（状态出 code 字符串）")
    void getByVisitPassesThroughServiceResult() throws Exception {
        when(depositService.getByVisit("I2026091600001"))
                .thenReturn(new DepositAccountVO(800L, 7L, "I2026091600001", 30000L, 10000L, "NORMAL"));

        String body = mockMvc.perform(get("/api/v1/billing/deposits").param("visitId", "I2026091600001"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"visitId\":\"I2026091600001\"")
                .contains("\"balance\":30000")
                .contains("\"status\":\"NORMAL\"");
        verify(depositService).getByVisit("I2026091600001");
    }
}
