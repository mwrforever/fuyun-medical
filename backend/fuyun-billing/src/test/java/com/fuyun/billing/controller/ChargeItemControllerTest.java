package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.api.BillingErrorCode;
import com.fuyun.billing.dto.ComboComponentRequest;
import com.fuyun.billing.entity.ChargeItem;
import com.fuyun.billing.enums.ItemClass;
import com.fuyun.billing.enums.ItemStatus;
import com.fuyun.billing.service.IChargeItemService;
import com.fuyun.common.exception.BizException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 收费项目端点薄层单测：建档 201/按码查 200/缺项 404 ProblemDetail（BILL-1001）/
 * 组合构成 204（controller 禁业务逻辑与事务，状态机守卫归 service 单测）。
 */
@ExtendWith(MockitoExtension.class)
class ChargeItemControllerTest {

    @Mock
    private IChargeItemService chargeItemService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new ChargeItemController(chargeItemService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    private ChargeItem itemRow() {
        ChargeItem item = new ChargeItem();
        item.setId(1L);
        item.setItemCode("C001");
        item.setItemName("血常规");
        item.setItemClass(ItemClass.TREATMENT);
        item.setUnit("次");
        item.setComboFlag(false);
        item.setFeeCategory("LAB_FEE");
        item.setStatus(ItemStatus.ACTIVE);
        return item;
    }

    @Test
    @DisplayName("新建项目端点：201 直出 VO（实体禁出网，静态工厂转出参）")
    void createReturnsCreatedVO() throws Exception {
        when(chargeItemService.createChargeItem(any())).thenReturn(1L);
        when(chargeItemService.getById(1L)).thenReturn(itemRow());

        String body = mockMvc.perform(post("/api/v1/billing/charge-items")
                        .contentType("application/json")
                        .content("{\"itemCode\":\"C001\",\"itemName\":\"血常规\",\"itemClass\":\"TREATMENT\","
                                + "\"unit\":\"次\",\"comboFlag\":false,\"feeCategory\":\"LAB_FEE\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"itemCode\":\"C001\"").contains("\"status\":\"ACTIVE\"");
        verify(chargeItemService).createChargeItem(any());
    }

    @Test
    @DisplayName("按码查生效项目端点：200 直出 VO")
    void getByCodeReturnsActiveVO() throws Exception {
        when(chargeItemService.requireActiveByCode("C001")).thenReturn(itemRow());

        String body = mockMvc.perform(get("/api/v1/billing/charge-items/by-code/C001"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"itemName\":\"血常规\"").contains("\"priceFlag\":null");
    }

    @Test
    @DisplayName("按码查生效项目端点：缺项经全局渲染 404 ProblemDetail 携 BILL-1001")
    void getByCodeRendersProblemDetailOnMissing() throws Exception {
        when(chargeItemService.requireActiveByCode("C404"))
                .thenThrow(
                        new BizException(BillingErrorCode.CHARGE_ITEM_NOT_FOUND, HttpStatus.NOT_FOUND, "收费项目不存在：C404"));

        String body = mockMvc.perform(get("/api/v1/billing/charge-items/by-code/C404"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"errorCode\":\"BILL-1001\"");
    }

    @Test
    @DisplayName("组合构成维护端点：204 无体且成员清单透传 service")
    void saveComboComponentsReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/billing/charge-items/9/combo-components")
                        .contentType("application/json")
                        .content("[{\"componentItemId\":12,\"defaultQuantity\":2.000}]"))
                .andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ComboComponentRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(chargeItemService).saveComboComponents(anyLong(), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).componentItemId()).isEqualTo(12L);
        assertThat(captor.getValue().get(0).defaultQuantity()).isEqualByComparingTo("2.000");
    }
}
