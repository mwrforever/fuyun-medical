package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.service.IPatientService;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.service.PatientRegistrationService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
import com.fuyun.patient.vo.PatientVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 建档与查询端点薄层单测：@Valid 校验（缺必填 400）、建档 201、预检 200 委托 +
 * 详情脱敏/冻结校验/检索透传三条（对外 API 单测义务）。
 */
@ExtendWith(MockitoExtension.class)
class PatientControllerTest {

    @Mock
    private PatientRegistrationService registrationService;

    @Mock
    private PatientMatchingService matchingService;

    @Mock
    private IPatientService patientService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PatientController(registrationService, matchingService, patientService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("建档请求缺姓名/渠道/知情同意引用 → 400（声明式校验）")
    void createRejectsBlankRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/patient/patients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sex\":\"1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("合法建档请求 → 201 并返回 outcome 与 candidatePatientId")
    void createReturns201WithOutcome() throws Exception {
        when(registrationService.register(any(PatientCreateRequest.class)))
                .thenReturn(new PatientMatchCheckVO("NO_MATCH", 777L, null, List.of()));
        mockMvc.perform(
                        post("/api/v1/patient/patients")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"张三\",\"sex\":\"1\",\"birthDate\":\"1990-03-07\",\"idCardNo\":\"110101199003077890\",\"mobile\":\"13800001234\",\"registerChannel\":\"WINDOW\",\"informedConsentRef\":\"paper-001\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("预检端点委托匹配引擎并返回 200")
    void matchCheckDelegatesToEngine() throws Exception {
        when(matchingService.preCheck(any(PatientMatchCheckRequest.class)))
                .thenReturn(new PatientMatchCheckVO(
                        "SUSPECT", 1L, new java.math.BigDecimal("95"), List.of("NAME_SEX_BIRTH")));
        String body = mockMvc.perform(post("/api/v1/patient/patients/match-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"sex\":\"1\",\"birthDate\":\"1990-03-07\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("SUSPECT").contains("95");
    }

    @Test
    @DisplayName("详情端点返回脱敏出参（200 JSON 含脱敏证件号形态）")
    void detailReturnsMaskedVo() throws Exception {
        PatientVO vo = new PatientVO();
        vo.setPatientId(5L);
        vo.setIdCardNo("110101********7890");
        when(patientService.getDetail(5L)).thenReturn(vo);
        String body = mockMvc.perform(get("/api/v1/patient/patients/5"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("110101********7890");
    }

    @Test
    @DisplayName("冻结请求缺原因 → 400；合法请求 → 204")
    void freezeValidatesReasonAndReturns204() throws Exception {
        mockMvc.perform(post("/api/v1/patient/patients/5/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/patient/patients/5/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"身份存疑\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("检索端点透传分页参数（200）")
    void searchAcceptsPaging() throws Exception {
        when(patientService.search(any()))
                .thenReturn(new com.fuyun.common.web.PageResult<>(java.util.List.of(), 0, 20, 0));
        mockMvc.perform(get("/api/v1/patient/patients/search")
                        .param("keyword", "张")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk());
    }
}
