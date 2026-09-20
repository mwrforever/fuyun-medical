package com.fuyun.pharmacy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.pharmacy.dto.InsuranceMappingRequest;
import com.fuyun.pharmacy.service.IDrugService;
import com.fuyun.pharmacy.vo.DrugVO;
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
 * 药品字典端点薄层单测（controller 禁业务逻辑，A.1-8）：建档/变更/详情/医保对照/选药检索
 * 五端点的路由、入参绑定与出参渲染（200 直出；对照端点 void 无体）。
 */
@ExtendWith(MockitoExtension.class)
class DrugControllerTest {

    @Mock
    private IDrugService drugService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new DrugController(drugService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("建档端点：POST /drugs 200 直出新药品 VO")
    void createReturnsCreatedVO() throws Exception {
        when(drugService.create(any())).thenReturn(vo());

        String body = mockMvc.perform(post("/api/v1/pharmacy/drugs")
                        .contentType("application/json")
                        .content("{\"drugCode\":\"D-IT-001\",\"genericName\":\"阿莫西林胶囊\","
                                + "\"essentialFlag\":false,\"antibioClass\":\"UNRESTRICTED\","
                                + "\"hazardLevel\":\"NONE\",\"skinTestFlag\":false,\"narcoticClass\":\"NORMAL\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"drugCode\":\"D-IT-001\"").contains("\"status\":\"ENABLED\"");
    }

    @Test
    @DisplayName("变更端点：PUT /drugs/{id} 200 直出变更后 VO（id 路径绑定）")
    void updateReturnsUpdatedVO() throws Exception {
        when(drugService.update(eq(7L), any())).thenReturn(vo());

        String body = mockMvc.perform(put("/api/v1/pharmacy/drugs/7")
                        .contentType("application/json")
                        .content("{\"drugCode\":\"D-IT-001\",\"genericName\":\"阿莫西林胶囊\","
                                + "\"essentialFlag\":false,\"antibioClass\":\"UNRESTRICTED\","
                                + "\"hazardLevel\":\"NONE\",\"skinTestFlag\":false,\"narcoticClass\":\"NORMAL\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"drugCode\":\"D-IT-001\"");
        verify(drugService).update(eq(7L), any());
    }

    @Test
    @DisplayName("详情端点：GET /drugs/{id} 200 直出 VO")
    void getReturnsVO() throws Exception {
        when(drugService.get(7L)).thenReturn(vo());

        String body = mockMvc.perform(get("/api/v1/pharmacy/drugs/7"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"insuredSettleable\":true");
        verify(drugService).get(7L);
    }

    @Test
    @DisplayName("医保对照端点：POST /drugs/{id}/insurance-mapping 200 无体，入参整体透传服务层")
    void mapInsurancePassesThrough() throws Exception {
        mockMvc.perform(
                        post("/api/v1/pharmacy/drugs/7/insurance-mapping")
                                .contentType("application/json")
                                .content(
                                        "{\"nhsaCode\":\"XJ01CAC0130020105139\",\"catalogVersion\":\"2024\",\"payType\":\"YI\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(""));

        ArgumentCaptor<InsuranceMappingRequest> captor = ArgumentCaptor.forClass(InsuranceMappingRequest.class);
        verify(drugService).mapInsurance(eq(7L), captor.capture());
        assertThat(captor.getValue().nhsaCode()).isEqualTo("XJ01CAC0130020105139");
        assertThat(captor.getValue().catalogVersion()).isEqualTo("2024");
        assertThat(captor.getValue().payType()).isEqualTo("YI");
    }

    @Test
    @DisplayName("检索端点：GET /drugs/search 查询参数与分页缺省绑定（page=0/size=20 缺省透传）")
    void searchBindsQueryParameters() throws Exception {
        when(drugService.search(any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(PageResult.of(List.of(vo()), 0, 20, 1));

        String body = mockMvc.perform(get("/api/v1/pharmacy/drugs/search")
                        .param("keyword", "阿莫")
                        .param("essential", "true")
                        .param("antibioClass", "UNRESTRICTED")
                        .param("insuranceMapped", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"total\":1").contains("\"drugCode\":\"D-IT-001\"");
        verify(drugService).search(eq("阿莫"), eq(Boolean.TRUE), eq("UNRESTRICTED"), eq(Boolean.TRUE), eq(0), eq(20));
    }

    /** 出参样例：已对照药品（对照三列在位，派生可结算=true） */
    private DrugVO vo() {
        return new DrugVO(
                7L,
                "D-IT-001",
                "阿莫西林胶囊",
                "阿莫仙",
                "胶囊剂",
                "0.25g×24粒",
                "华东医药",
                List.of("ORAL", "IV"),
                "盒",
                new java.math.BigDecimal("1"),
                "XJ01CAC0130020589",
                "2024",
                "YI",
                false,
                "UNRESTRICTED",
                "NONE",
                false,
                "NORMAL",
                "C0131230900157",
                null,
                "用于敏感菌所致感染",
                "成人一日不超过4g",
                "青霉素过敏者禁用",
                "密封，置阴凉处保存",
                true,
                "ENABLED");
    }
}
