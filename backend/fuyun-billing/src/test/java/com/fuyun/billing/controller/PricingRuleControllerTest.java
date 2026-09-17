package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.dto.PricingRuleUpsertRequest;
import com.fuyun.billing.entity.PricingRule;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.enums.TriggerType;
import com.fuyun.billing.service.IPricingRuleService;
import com.fuyun.common.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 计价规则端点薄层单测：登记 upsert 回传落库 id（200；item_scope JSON 守卫归 service 单测）
 * 与全量清单 200 直出 VO 数组。
 */
@ExtendWith(MockitoExtension.class)
class PricingRuleControllerTest {

    @Mock
    private IPricingRuleService pricingRuleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new PricingRuleController(pricingRuleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("规则登记端点：200 直出落库行 id")
    void upsertReturnsRuleId() throws Exception {
        when(pricingRuleService.upsert(any(PricingRuleUpsertRequest.class))).thenReturn(66L);

        String body = mockMvc.perform(post("/api/v1/billing/pricing-rules/upsert")
                        .contentType("application/json")
                        .content("{\"ruleCode\":\"R-001\",\"ruleName\":\"开单确认计价\",\"triggerType\":\"ORDER_CONFIRMED\","
                                + "\"itemScope\":\"{\\\"itemClasses\\\":[\\\"WEST_DRUG\\\"]}\",\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("66");
        ArgumentCaptor<PricingRuleUpsertRequest> captor = ArgumentCaptor.forClass(PricingRuleUpsertRequest.class);
        verify(pricingRuleService).upsert(captor.capture());
        assertThat(captor.getValue().triggerType()).isEqualTo(TriggerType.ORDER_CONFIRMED);
        assertThat(captor.getValue().itemScope()).isEqualTo("{\"itemClasses\":[\"WEST_DRUG\"]}");
    }

    @Test
    @DisplayName("规则清单端点：200 直出 VO 数组（实体禁出网）")
    void listAllReturnsVOArray() throws Exception {
        PricingRule first = new PricingRule();
        first.setId(1L);
        first.setRuleCode("R-001");
        first.setRuleName("开单确认计价");
        first.setTriggerType(TriggerType.ORDER_CONFIRMED);
        first.setItemScope("{\"itemIds\":[1]}");
        first.setStatus(ItemStatus.ACTIVE);
        PricingRule second = new PricingRule();
        second.setId(2L);
        second.setRuleCode("R-002");
        second.setRuleName("执行完成计价");
        second.setTriggerType(TriggerType.EXECUTED);
        second.setItemScope("{\"itemIds\":[2]}");
        second.setStatus(ItemStatus.INACTIVE);
        when(pricingRuleService.listAll()).thenReturn(List.of(first, second));

        String body = mockMvc.perform(get("/api/v1/billing/pricing-rules"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"ruleCode\":\"R-001\"")
                .contains("\"ruleCode\":\"R-002\"")
                .contains("\"triggerType\":\"EXECUTED\"");
    }
}
