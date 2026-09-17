package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.dto.QuoteRequest;
import com.fuyun.billing.entity.FeeRecord;
import com.fuyun.billing.enums.ChargeSource;
import com.fuyun.billing.enums.ExecOccupyStatus;
import com.fuyun.billing.enums.FeeStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.enums.VisitType;
import com.fuyun.billing.service.IPricingEngineService;
import com.fuyun.billing.vo.QuoteVO;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 费用端点薄层冒烟单测（JaCoCo BUNDLE 兜底）：预计价透传 200/手工计费 @Valid 缺要素 400（服务零交互）/
 * 作废透传 204/费用查询分页 VO 出网（controller 禁业务逻辑与事务，计价与状态机守卫归引擎单测）。
 */
@ExtendWith(MockitoExtension.class)
class FeeControllerTest {

    @Mock
    private IPricingEngineService engine;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new FeeController(engine))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("预计价端点：合法请求 200 且 mock 返 VO 原样出参（不落库语义由引擎单测锁定）")
    void quotePassesThroughEngineResult() throws Exception {
        when(engine.quote(any(QuoteRequest.class)))
                .thenReturn(new QuoteVO(
                        "O2026091700001",
                        3000L,
                        List.of(new QuoteVO.Line(100L, "C001", "检查费", 1500L, new BigDecimal("2"), 3000L, true))));

        String body = mockMvc.perform(post("/api/v1/billing/pricing/quote")
                        .contentType("application/json")
                        .content("{\"patientId\":7,\"visitId\":\"O2026091700001\","
                                + "\"lines\":[{\"itemCode\":\"C001\",\"quantity\":2}]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"totalAmount\":3000").contains("\"selfExpenseOnly\":true");
        verify(engine).quote(any(QuoteRequest.class));
    }

    @Test
    @DisplayName("手工计费端点：缺 reason 字段 @Valid 400 且服务零交互（controller 禁业务逻辑）")
    void manualRejectsMissingReasonAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(
                        post("/api/v1/billing/fees/manual")
                                .contentType("application/json")
                                .content(
                                        "{\"patientId\":7,\"visitId\":\"O2026091700001\",\"itemCode\":\"C001\",\"quantity\":1}"))
                .andExpect(status().isBadRequest());

        verify(engine, never()).manualCharge(any());
    }

    @Test
    @DisplayName("手工计费端点：合法请求 201 且透传引擎返行 id（命令组装归服务薄编排层，controller 不组装命令）")
    void manualPassesThroughEngineReturns201() throws Exception {
        when(engine.manualCharge(any())).thenReturn(77L);

        mockMvc.perform(post("/api/v1/billing/fees/manual")
                        .contentType("application/json")
                        .content("{\"patientId\":7,\"visitId\":\"O2026091700001\",\"itemCode\":\"C001\","
                                + "\"quantity\":1,\"reason\":\"患者补收治疗费\"}"))
                .andExpect(status().isCreated());

        verify(engine).manualCharge(any());
    }

    @Test
    @DisplayName("作废端点：204 无体且理由透传引擎（WRITE 审计落点由切面承载）")
    void cancelPassesThroughEngine() throws Exception {
        mockMvc.perform(post("/api/v1/billing/fees/1/cancel").param("reason", "开错项目当日更正"))
                .andExpect(status().isNoContent());

        verify(engine).cancel(eq(1L), any());
    }

    @Test
    @DisplayName("费用查询端点：0 基分页透传，实体经静态工厂转 VO 出网（枚举转 code，禁实体直出）")
    void listByVisitReturnsPagedVOs() throws Exception {
        FeeRecord row = new FeeRecord();
        row.setId(9L);
        row.setFeeNo("F1");
        row.setPatientId(7L);
        row.setVisitId("O2026091700001");
        row.setVisitType(VisitType.OUT);
        row.setChargeItemId(100L);
        row.setItemNameSnapshot("检查费");
        row.setUnitPriceSnapshot(1500L);
        row.setQuantity(new BigDecimal("2"));
        row.setAmount(3000L);
        row.setChargeSource(ChargeSource.ORDER_LINKED);
        row.setTriggerPoint(TriggerType.ORDER_CONFIRMED);
        row.setExecOccupyStatus(ExecOccupyStatus.NONE);
        row.setBillingDate(java.time.LocalDate.of(2026, 9, 17));
        row.setStatus(FeeStatus.PENDING);
        when(engine.pageByVisit("O2026091700001", 0, 20)).thenReturn(PageResult.of(List.of(row), 0, 20, 1));

        String body = mockMvc.perform(get("/api/v1/billing/fees?visitId=O2026091700001&page=0&size=20"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"itemNameSnapshot\":\"检查费\"")
                .contains("\"status\":\"PENDING\"")
                .contains("\"visitType\":\"OUT\"")
                .contains("\"total\":1")
                .contains("\"page\":0");
        verify(engine).pageByVisit("O2026091700001", 0, 20);
    }
}
