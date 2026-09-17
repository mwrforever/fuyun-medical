package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.dto.SettleRequest;
import com.fuyun.billing.dto.SettlementPreviewRequest;
import com.fuyun.billing.service.ISettlementService;
import com.fuyun.billing.vo.SettlementPreviewVO;
import com.fuyun.billing.vo.SettlementVO;
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
 * 结算端点薄层冒烟单测（JaCoCo BUNDLE 兜底）：预结算透传 200 且 VO 原样出参/正式结算缺 payments
 * @Valid 400（服务零交互）/合法结算透传 200/按编号查询透传 200（controller 禁业务逻辑与事务，
 * 勾稽/幂等/台账记账守卫归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class SettlementControllerTest {

    @Mock
    private ISettlementService settlementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new SettlementController(settlementService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("预结算端点：合法请求 200 且 mock 返 VO 原样出参（勾稽与草稿落库语义由服务单测锁定）")
    void previewPassesThroughServiceResult() throws Exception {
        when(settlementService.preview(any(SettlementPreviewRequest.class)))
                .thenReturn(new SettlementPreviewVO(
                        "S100", 7L, "O2026091700001", "SELF_PAY", "DRAFT", 5000L, null, null, null, 5000L, null));

        String body = mockMvc.perform(post("/api/v1/billing/settlements/preview")
                        .contentType("application/json")
                        .content("{\"patientId\":7,\"visitId\":\"O2026091700001\",\"payerType\":\"SELF_PAY\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"totalAmount\":5000")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"settleNo\":\"S100\"");
        verify(settlementService).preview(any(SettlementPreviewRequest.class));
    }

    @Test
    @DisplayName("正式结算端点：缺 payments 字段 @Valid 400 且服务零交互（controller 禁业务逻辑）")
    void settleRejectsMissingPaymentsAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/billing/settlements")
                        .contentType("application/json")
                        .content("{\"settleNo\":\"S100\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlementService);
    }

    @Test
    @DisplayName("正式结算端点：合法请求 200 且透传服务返 VO（controller 直传 SettleRequest 不拆参）")
    void settlePassesThroughServiceResult() throws Exception {
        when(settlementService.settle(any(SettleRequest.class)))
                .thenReturn(new SettlementVO(
                        900L,
                        "S100",
                        7L,
                        "O2026091700001",
                        "SELF_PAY",
                        "OUT",
                        "SETTLED",
                        5000L,
                        null,
                        null,
                        null,
                        5000L,
                        null,
                        "2026-09-17T10:00+08:00"));

        String body = mockMvc.perform(post("/api/v1/billing/settlements")
                        .contentType("application/json")
                        .content("{\"settleNo\":\"S100\",\"payments\":[{\"method\":\"CASH\",\"amount\":5000}]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"settleNo\":\"S100\"")
                .contains("\"status\":\"SETTLED\"")
                .contains("\"totalAmount\":5000");
        verify(settlementService).settle(any(SettleRequest.class));
    }

    @Test
    @DisplayName("结算查询端点：按编号 200 透传 VO（枚举出 code、settledAt 出 ISO-8601 文本）")
    void getByNoPassesThroughServiceResult() throws Exception {
        when(settlementService.getByNo("S100"))
                .thenReturn(new SettlementVO(
                        900L,
                        "S100",
                        7L,
                        "O2026091700001",
                        "SELF_PAY",
                        "OUT",
                        "SETTLED",
                        5000L,
                        null,
                        null,
                        null,
                        5000L,
                        null,
                        "2026-09-17T10:00+08:00"));

        String body = mockMvc.perform(get("/api/v1/billing/settlements/S100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"settleType\":\"OUT\"").contains("\"settledAt\":\"2026-09-17T10:00+08:00\"");
        verify(settlementService).getByNo("S100");
    }
}
