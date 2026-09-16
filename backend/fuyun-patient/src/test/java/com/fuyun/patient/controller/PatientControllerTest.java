package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.patient.dto.PatientCreateRequest;
import com.fuyun.patient.dto.PatientMatchCheckRequest;
import com.fuyun.patient.service.PatientMatchingService;
import com.fuyun.patient.service.PatientRegistrationService;
import com.fuyun.patient.vo.PatientMatchCheckVO;
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
 * 建档端点薄层单测：@Valid 校验（缺必填 400）、建档 201、预检 200 委托三条（对外 API 单测义务）。
 */
@ExtendWith(MockitoExtension.class)
class PatientControllerTest {

    @Mock
    private PatientRegistrationService registrationService;

    @Mock
    private PatientMatchingService matchingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PatientController(registrationService, matchingService))
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
}
