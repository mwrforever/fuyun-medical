package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.patient.api.PatientContextResolver;
import com.fuyun.patient.api.PatientContextView;
import com.fuyun.patient.entity.PatientIdentifier;
import com.fuyun.patient.service.IPatientIdentifierService;
import java.nio.charset.StandardCharsets;
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
 * 标识端点薄层单测：解析 200 归一视图、补挂卡类缺卡面号 400、补挂成功 201 + identifier.changed
 * 发布、清单只出卡面号不出标识值（对外 API 单测义务 + 敏感红线）。
 */
@ExtendWith(MockitoExtension.class)
class PatientIdentifierControllerTest {

    @Mock
    private IPatientIdentifierService identifierService;

    @Mock
    private PatientContextResolver contextResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new PatientIdentifierController(identifierService, contextResolver))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("标识解析：命中 ACTIVE 行后返回归一主档视图（200）")
    void resolveReturnsNormalizedView() throws Exception {
        PatientIdentifier row = new PatientIdentifier();
        row.setId(1L);
        row.setPatientId(2L);
        row.setStatus("ACTIVE");
        when(identifierService.resolveActive(any(), any())).thenReturn(row);
        when(contextResolver.resolve(2L)).thenReturn(new PatientContextView(2L, 1L, "NORMAL", false, ""));

        String body = mockMvc.perform(post("/api/v1/patient/identifiers/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifierType\":\"ID_CARD\",\"identifierValue\":\"110101199003077890\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"resolvedPatientId\":1");
    }

    @Test
    @DisplayName("补挂卡类标识缺卡面号 → 400")
    void attachCardWithoutCardNoRejected() throws Exception {
        mockMvc.perform(post("/api/v1/patient/patients/5/identifiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifierType\":\"VISIT_CARD\",\"identifierValue\":\"1234567890\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("补挂非卡标识（ID_CARD）→ 201，且发布 identifier.changed（BOUND）")
    void attachNonCardReturns201AndPublishesChanged() throws Exception {
        PatientIdentifier row = new PatientIdentifier();
        row.setId(66L);
        row.setPatientId(5L);
        row.setIdentifierType("ID_CARD");
        row.setStatus("ACTIVE");
        when(identifierService.attach(5L, "ID_CARD", "110101199003077890", null, false))
                .thenReturn(66L);
        when(identifierService.getById(66L)).thenReturn(row);

        String body = mockMvc.perform(post("/api/v1/patient/patients/5/identifiers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifierType\":\"ID_CARD\",\"identifierValue\":\"110101199003077890\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"id\":66").contains("\"status\":\"ACTIVE\"");
        // identifier.changed 发布点在补挂链路收口：变更类型 BOUND、载荷只携盲索引（载荷无明文由服务单测锁定）
        verify(identifierService).publishChanged(5L, "ID_CARD", "110101199003077890", "BOUND");
    }

    @Test
    @DisplayName("标识清单：只出卡面号，不出标识值密文/盲索引列（敏感红线）")
    void listOmitsIdentifierValueColumns() throws Exception {
        PatientIdentifier row = new PatientIdentifier();
        row.setId(1L);
        row.setPatientId(5L);
        row.setIdentifierType("HEALTH_CARD");
        row.setIdentifierValueCipher("cipher-value");
        row.setValueHash("hash-value");
        row.setCardNo("CARD-0001");
        row.setStatus("ACTIVE");
        when(identifierService.listByPatient(5L)).thenReturn(List.of(row));

        String body = mockMvc.perform(get("/api/v1/patient/patients/5/identifiers"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("CARD-0001").doesNotContain("cipher-value", "hash-value");
    }
}
