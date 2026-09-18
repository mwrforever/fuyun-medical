package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fuyun.billing.service.IDailyListService;
import com.fuyun.billing.vo.DailyListVO;
import com.fuyun.common.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * 一日清单端点薄层冒烟单测（JaCoCo BUNDLE 兜底）：visitId+date 透传 200 且 VO 原样出参/
 * 缺 date 参数 400（服务零交互；controller 禁业务逻辑与事务，三层勾稽装配归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class DailyListControllerTest {

    @Mock
    private IDailyListService dailyListService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按生产行为出 ProblemDetail；Jackson 关闭时间戳数组形态
        // （对齐 Boot 生产默认 WRITE_DATES_AS_TIMESTAMPS=disabled，LocalDate 出 ISO 字符串）
        mockMvc = MockMvcBuilders.standaloneSetup(new DailyListController(dailyListService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    @DisplayName("清单端点：visitId+date 200 透传 VO（明细/大类/总额三层载体，date 按 ISO 日期绑定）")
    void dailyListPassesThroughServiceResult() throws Exception {
        when(dailyListService.dailyList(any(), any()))
                .thenReturn(new DailyListVO(
                        "I2026091600001",
                        LocalDate.of(2026, 9, 16),
                        java.util.List.of(),
                        java.util.List.of(new DailyListVO.CategorySummary("西药费", 5000L)),
                        5000L));

        String body = mockMvc.perform(get("/api/v1/billing/daily-lists")
                        .param("visitId", "I2026091600001")
                        .param("date", "2026-09-16"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"visitId\":\"I2026091600001\"")
                .contains("\"date\":\"2026-09-16\"")
                .contains("\"feeCategory\":\"西药费\"")
                .contains("\"totalAmount\":5000");
        // date 必须按 ISO 日期解析为 LocalDate 透传（非字符串误传）
        verify(dailyListService).dailyList(eq("I2026091600001"), eq(LocalDate.of(2026, 9, 16)));
    }

    @Test
    @DisplayName("清单端点：缺 date 参数 400 且服务零交互（必填查询参数缺失由框架拒）")
    void dailyListRejectsMissingDateAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(get("/api/v1/billing/daily-lists").param("visitId", "I2026091600001"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dailyListService);
    }
}
