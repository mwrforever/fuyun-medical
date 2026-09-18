package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.dto.RefundApplyRequest;
import com.fuyun.billing.entity.RefundRequest;
import com.fuyun.billing.enums.RefundStatus;
import com.fuyun.billing.enums.RefundType;
import com.fuyun.billing.service.IRefundService;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
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
 * 退费端点薄层冒烟单测（JaCoCo BUNDLE 兜底）：申请透传 201/缺理由 @Valid 400（服务零交互）/
 * 审批与执行透传 204/驳回缺理由 400/分页查询 VO 出参与 status 筛选透传（controller 禁业务逻辑
 * 与事务，双人守卫/占用硬前置/判态迁移守卫归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class RefundControllerTest {

    @Mock
    private IRefundService refundService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new RefundController(refundService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("退费申请端点：合法请求 201 且透传服务返回 id（分级与算额语义由服务单测锁定）")
    void applyReturnsCreatedIdFromService() throws Exception {
        when(refundService.apply(any(RefundApplyRequest.class))).thenReturn(100L);

        String body = mockMvc.perform(
                        post("/api/v1/billing/refunds")
                                .contentType("application/json")
                                .content(
                                        "{\"settlementId\":900,\"lines\":[{\"feeId\":1,\"refundQuantity\":1}],\"reason\":\"当日更正\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("100");
        verify(refundService).apply(any(RefundApplyRequest.class));
    }

    @Test
    @DisplayName("退费申请端点：缺 reason @Valid 400 且服务零交互（controller 禁业务逻辑）")
    void applyRejectsBlankReasonAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/billing/refunds")
                        .contentType("application/json")
                        .content("{\"settlementId\":900,\"lines\":[{\"feeId\":1,\"refundQuantity\":1}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(refundService);
    }

    @Test
    @DisplayName("退费申请端点：refundQuantity 0/负数 @Positive 400 且服务零交互（值域与 DepositRequest @Positive 先例一致）")
    void applyRejectsNonPositiveQuantityAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(
                        post("/api/v1/billing/refunds")
                                .contentType("application/json")
                                .content(
                                        "{\"settlementId\":900,\"lines\":[{\"feeId\":1,\"refundQuantity\":0}],\"reason\":\"当日更正\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/v1/billing/refunds")
                                .contentType("application/json")
                                .content(
                                        "{\"settlementId\":900,\"lines\":[{\"feeId\":1,\"refundQuantity\":-1}],\"reason\":\"当日更正\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(refundService);
    }

    @Test
    @DisplayName("审批端点：POST /refunds/{id}/approve 透传 204（双人守卫归服务层）")
    void approvePassesThroughWith204() throws Exception {
        mockMvc.perform(post("/api/v1/billing/refunds/100/approve")).andExpect(status().isNoContent());

        verify(refundService).approve(100L);
    }

    @Test
    @DisplayName("驳回端点：合法请求 204 透传理由（终态留痕归服务层）")
    void rejectPassesThroughReason() throws Exception {
        mockMvc.perform(post("/api/v1/billing/refunds/100/reject")
                        .contentType("application/json")
                        .content("{\"reason\":\"凭证不符\"}"))
                .andExpect(status().isNoContent());
        verify(refundService).reject(100L, "凭证不符");
    }

    @Test
    @DisplayName("驳回端点：缺理由（空白）@Valid 400 且服务零交互（controller 禁业务逻辑）")
    void rejectRejectsBlankReasonAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/billing/refunds/100/reject")
                        .contentType("application/json")
                        .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(refundService);
    }

    @Test
    @DisplayName("执行端点：POST /refunds/{id}/execute 透传 204（原路退回归服务层）")
    void executePassesThroughWith204() throws Exception {
        mockMvc.perform(post("/api/v1/billing/refunds/100/execute")).andExpect(status().isNoContent());

        verify(refundService).execute(100L);
    }

    @Test
    @DisplayName("分页查询端点：status 筛选透传且 VO 出参（枚举出 code）；无筛选透传 null=全部")
    void listPassesStatusFilterAndReturnsVos() throws Exception {
        when(refundService.page(RefundStatus.PENDING_APPROVAL, 0, 20))
                .thenReturn(PageResult.of(List.of(refundRow()), 0, 20, 1));

        String body = mockMvc.perform(get("/api/v1/billing/refunds")
                        .param("status", "PENDING_APPROVAL")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"refundNo\":\"R100\"")
                .contains("\"refundType\":\"CROSS_DAY\"")
                .contains("\"status\":\"PENDING_APPROVAL\"")
                .contains("\"total\":1");
        verify(refundService).page(RefundStatus.PENDING_APPROVAL, 0, 20);

        // status 缺省=全部：服务层收到 null（条件缺席即全状态视图）
        when(refundService.page(isNull(), eq(0), eq(20))).thenReturn(PageResult.of(List.of(), 0, 20, 0));
        mockMvc.perform(get("/api/v1/billing/refunds")).andExpect(status().isOk());
        verify(refundService).page(isNull(), eq(0), eq(20));
    }

    private RefundRequest refundRow() {
        RefundRequest row = new RefundRequest();
        row.setId(100L);
        row.setRefundNo("R100");
        row.setSettlementId(900L);
        row.setPatientId(7L);
        row.setVisitId("O2026091700001");
        row.setRefundType(RefundType.CROSS_DAY);
        row.setAmount(3000L);
        row.setReason("跨日退费");
        row.setApplicant("cashier-1");
        row.setAutoApproved(false);
        row.setStatus(RefundStatus.PENDING_APPROVAL);
        return row;
    }
}
