package com.fuyun.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.billing.entity.InsuranceCallLog;
import com.fuyun.billing.enums.InsuranceCallStatus;
import com.fuyun.billing.gateway.InsuranceGateway;
import com.fuyun.billing.service.IInsuranceCallLogService;
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
 * 医保基线端点薄层冒烟单测（FU-M13-05 接口位，JaCoCo BUNDLE 兜底）：登记/费用上传/撤销/凭证核验
 * 透传 200、空凭证 @Valid 400（网关零触碰——服务层 BILL-1024 双守卫归适应器单测）、补偿重试透传
 * 204、留痕分页 VO 出网（controller 禁业务逻辑与事务，状态守卫归服务单测）。
 */
@ExtendWith(MockitoExtension.class)
class InsuranceControllerTest {

    @Mock
    private InsuranceGateway insuranceGateway;

    @Mock
    private IInsuranceCallLogService callLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new InsuranceController(insuranceGateway, callLogService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("门诊登记端点（2001）：透传网关确定性流水号出网")
    void registerPassesThroughGatewaySerial() throws Exception {
        when(insuranceGateway.register("O2026091700001", 7L)).thenReturn("SIM-REG-O2026091700001");

        String body = mockMvc.perform(post("/api/v1/billing/insurance/register")
                        .param("visitId", "O2026091700001")
                        .param("patientId", "7"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("SIM-REG-O2026091700001");
        verify(insuranceGateway).register("O2026091700001", 7L);
    }

    @Test
    @DisplayName("费用上传端点（2101）：透传网关并回显上传行数")
    void feeUploadPassesThroughGatewayCount() throws Exception {
        when(insuranceGateway.feeUpload("O2026091700001", List.of(1L, 2L))).thenReturn(2);

        String body = mockMvc.perform(post("/api/v1/billing/insurance/fee-uploads")
                        .param("visitId", "O2026091700001")
                        .contentType("application/json")
                        .content("[1,2]"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("2");
        verify(insuranceGateway).feeUpload("O2026091700001", List.of(1L, 2L));
    }

    @Test
    @DisplayName("医保撤销端点（2104）：透传网关撤销流水号（WRITE 审计挂注解，冒烟不校验切面）")
    void reversePassesThroughGatewaySerial() throws Exception {
        when(insuranceGateway.reverse("SIM-S100", "REV-1")).thenReturn("SIM-REV-REV-1");

        String body = mockMvc.perform(post("/api/v1/billing/insurance/reverse")
                        .param("centerSerialNo", "SIM-S100")
                        .param("idempotencyKey", "REV-1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("SIM-REV-REV-1");
        verify(insuranceGateway).reverse("SIM-S100", "REV-1");
    }

    @Test
    @DisplayName("电子凭证核验端点：合法 ecToken 200 直出 {\"authSerialNo\":\"SIM-AUTH-…\"} 契约形态")
    void credentialPassesThroughGatewayAuthSerial() throws Exception {
        when(insuranceGateway.authenticate("EC-TOKEN-42")).thenReturn("SIM-AUTH-EC-TOKEN-42");

        String body = mockMvc.perform(post("/api/v1/billing/insurance/credential")
                        .contentType("application/json")
                        .content("{\"ecToken\":\"EC-TOKEN-42\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("{\"authSerialNo\":\"SIM-AUTH-EC-TOKEN-42\"}");
        verify(insuranceGateway).authenticate("EC-TOKEN-42");
    }

    @Test
    @DisplayName("电子凭证核验端点：空 ecToken @Valid 400 且网关零触碰（边界拒空先于服务层 BILL-1024）")
    void credentialRejectsBlankTokenAs400WithoutGatewayCall() throws Exception {
        mockMvc.perform(post("/api/v1/billing/insurance/credential")
                        .contentType("application/json")
                        .content("{\"ecToken\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(insuranceGateway);
    }

    @Test
    @DisplayName("补偿重试端点：透传服务层 204（BILL-1026 状态守卫归服务单测）")
    void compensatePassesThroughServiceNote() throws Exception {
        mockMvc.perform(post("/api/v1/billing/insurance/call-logs/9/compensation")
                        .contentType("application/json")
                        .content("{\"note\":\"悬挂冲正完成\"}"))
                .andExpect(status().isNoContent());

        verify(callLogService).compensate(9L, "悬挂冲正完成");
    }

    @Test
    @DisplayName("补偿重试端点：空结论 @Valid 400 且服务零交互（留痕必填红线）")
    void compensateRejectsBlankNoteAs400WithoutServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/billing/insurance/call-logs/9/compensation")
                        .contentType("application/json")
                        .content("{\"note\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(callLogService);
    }

    @Test
    @DisplayName("留痕分页端点：实体经 VO 静态工厂出网（禁实体直出，枚举出 code）")
    void callLogsMapsEntitiesToVos() throws Exception {
        InsuranceCallLog row = new InsuranceCallLog();
        row.setId(1L);
        row.setTxnCode("2102");
        row.setVisitId("O2026091700001");
        row.setRequestDigest("preview|total=10000");
        row.setCenterSerialNo("SIM-S100");
        row.setResultCode("0000");
        row.setStatus(InsuranceCallStatus.SUCCESS);
        when(callLogService.page(any(), anyInt(), anyInt())).thenReturn(PageResult.of(List.of(row), 0, 20, 1));

        String body = mockMvc.perform(get("/api/v1/billing/insurance/call-logs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"txnCode\":\"2102\"")
                .contains("\"status\":\"SUCCESS\"")
                .contains("\"total\":1");
        verify(callLogService).page(null, 0, 20); // visitId 缺省=全量（运营排查视图）
    }
}
