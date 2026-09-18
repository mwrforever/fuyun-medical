package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.entity.ChargeItemPrice;
import com.fuyun.billing.enums.PriceSource;
import com.fuyun.billing.enums.PriceStatus;
import com.fuyun.billing.service.IChargePriceService;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 调价端点薄层单测：草稿 201/发布 204/重复发布 409 ProblemDetail（BILL-1028）/
 * 版本链 200 直出 VO（controller 禁业务逻辑与事务，状态机守卫与广播归 service 单测）。
 */
@ExtendWith(MockitoExtension.class)
class PriceAdjustmentControllerTest {

    @Mock
    private IChargePriceService chargePriceService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new PriceAdjustmentController(chargePriceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private ChargeItemPrice priceRow() {
        ChargeItemPrice row = new ChargeItemPrice();
        row.setId(9L);
        row.setChargeItemId(100L);
        row.setPrice(4000L);
        row.setVersion(4);
        row.setEffectiveFrom(OffsetDateTime.parse("2026-10-01T00:00+08:00"));
        row.setPriceSource(PriceSource.OFFICIAL_DOC);
        row.setApprovalNo("物价批文〔2026〕7 号");
        row.setStatus(PriceStatus.DRAFT);
        return row;
    }

    @Test
    @DisplayName("新建草稿端点：201 直出草稿行 id（发布端点入参，请求体透传 service）")
    void createDraftReturnsCreatedId() throws Exception {
        when(chargePriceService.saveDraft(any())).thenReturn(9L);

        String body = mockMvc.perform(post("/api/v1/billing/charge-items/100/prices")
                        .contentType("application/json")
                        .content("{\"itemCode\":\"C001\",\"price\":4000,"
                                + "\"effectiveFrom\":\"2026-10-01T00:00:00+08:00\",\"priceSource\":\"OFFICIAL_DOC\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("9");
        verify(chargePriceService).saveDraft(any());
    }

    @Test
    @DisplayName("发布端点：204 无体且行 id 透传 service（闭旧区间与广播归 service）")
    void publishReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/billing/price-adjustments/9/publish")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNoContent());

        verify(chargePriceService).publish(9L);
    }

    @Test
    @DisplayName("发布端点：非 DRAFT 重复发布经全局渲染 409 ProblemDetail 携 BILL-1028")
    void publishRendersProblemDetailOnStateViolation() throws Exception {
        doThrow(new BizException(BillingErrorCode.PRICE_STATE_NOT_ALLOWED, HttpStatus.CONFLICT, "仅 DRAFT 版本可发布"))
                .when(chargePriceService)
                .publish(9L);

        String body = mockMvc.perform(post("/api/v1/billing/price-adjustments/9/publish"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"errorCode\":\"BILL-1028\"");
    }

    @Test
    @DisplayName("版本链查询端点：200 直出 VO 清单（实体禁出网，静态工厂转换）")
    void listVersionsReturnsVOChain() throws Exception {
        when(chargePriceService.listVersions(100L)).thenReturn(List.of(priceRow()));

        String body = mockMvc.perform(get("/api/v1/billing/charge-items/100/prices"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"id\":9")
                .contains("\"price\":4000")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"priceSource\":\"OFFICIAL_DOC\"");
    }
}
