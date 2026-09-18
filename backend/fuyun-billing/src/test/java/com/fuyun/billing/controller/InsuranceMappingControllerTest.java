package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.dto.InsuranceMappingUpsertRequest;
import com.fuyun.billing.entity.InsuranceMapping;
import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import com.fuyun.billing.enums.MappingStatus;
import com.fuyun.billing.service.IInsuranceMappingService;
import com.fuyun.common.web.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 医保对照端点薄层单测：登记 upsert 回传落库 id（200）与生效对照查询（命中 200 直出 VO /
 * 未贯标 204 无体——查询态不报错，贯标硬校验归结算 preview 分支）。
 */
@ExtendWith(MockitoExtension.class)
class InsuranceMappingControllerTest {

    @Mock
    private IInsuranceMappingService insuranceMappingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new InsuranceMappingController(insuranceMappingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private InsuranceMapping mappingRow() {
        InsuranceMapping mapping = new InsuranceMapping();
        mapping.setId(9L);
        mapping.setChargeItemId(5L);
        mapping.setMapType(MapType.TREATMENT);
        mapping.setNhsaCode("NHBZ-TREAT-001");
        mapping.setCatalogVersion("2026.0");
        mapping.setSelfPayRatio(new BigDecimal("0.1000"));
        mapping.setLimitPrice(50000L);
        mapping.setInsurancePayType(InsurancePayType.CLASS_B);
        mapping.setStatus(MappingStatus.ACTIVE);
        return mapping;
    }

    @Test
    @DisplayName("对照登记端点：200 直出落库行 id（改写原行 id / 新插回填雪花 id）")
    void upsertReturnsMappingId() throws Exception {
        when(insuranceMappingService.upsert(any(InsuranceMappingUpsertRequest.class)))
                .thenReturn(88L);

        String body = mockMvc.perform(post("/api/v1/billing/insurance-mappings/upsert")
                        .contentType("application/json")
                        .content("{\"chargeItemId\":5,\"mapType\":\"TREATMENT\",\"nhsaCode\":\"NHBZ-TREAT-001\","
                                + "\"catalogVersion\":\"2026.0\",\"selfPayRatio\":0.1,\"limitPrice\":50000,"
                                + "\"insurancePayType\":\"CLASS_B\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("88");
        ArgumentCaptor<InsuranceMappingUpsertRequest> captor =
                ArgumentCaptor.forClass(InsuranceMappingUpsertRequest.class);
        verify(insuranceMappingService).upsert(captor.capture());
        assertThat(captor.getValue().chargeItemId()).isEqualTo(5L);
        assertThat(captor.getValue().nhsaCode()).isEqualTo("NHBZ-TREAT-001");
    }

    @Test
    @DisplayName("生效对照查询端点：命中 ACTIVE 返 200 直出 VO")
    void effectiveReturnsVOWhenActive() throws Exception {
        when(insuranceMappingService.effectiveMapping(5L)).thenReturn(mappingRow());

        String body = mockMvc.perform(get("/api/v1/billing/insurance-mappings/5"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"nhsaCode\":\"NHBZ-TREAT-001\"").contains("\"status\":\"ACTIVE\"");
    }

    @Test
    @DisplayName("生效对照查询端点：未贯标返 204 无体（正常态非错误）")
    void effectiveReturns204WhenAbsent() throws Exception {
        when(insuranceMappingService.effectiveMapping(5L)).thenReturn(null);

        mockMvc.perform(get("/api/v1/billing/insurance-mappings/5")).andExpect(status().isNoContent());
        verify(insuranceMappingService).effectiveMapping(5L);
    }
}
