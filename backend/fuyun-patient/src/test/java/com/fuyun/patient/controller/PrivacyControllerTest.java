package com.fuyun.patient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.service.PrivacyMaskService;
import com.fuyun.patient.service.PrivacyService;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.PrivacyMaskRuleVO;
import com.fuyun.patient.vo.UnmaskVO;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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
 * 隐私端点薄层单测（M02 Spec §7）：unmask 缺 purpose 400 校验拒绝、unmask 200 值集委托直出与
 * 台账分页 200（controller 禁业务逻辑与事务，豁免/落痕归 service 单测；SENSITIVE_QUERY/WRITE
 * 审计落点由切面承载，standalone 骨架不加载）。
 *
 * <p>SEC-01 安全收口用例：脱敏规则维护写端点 ADMIN 门禁——非 ADMIN（含空角色）403 PAT-1024
 * 不触达服务、ADMIN 200 委托直出；读端点（规则清单）与 unmask 链路无 ADMIN 亦行为保持。
 */
@ExtendWith(MockitoExtension.class)
class PrivacyControllerTest {

    @Mock
    private IPrivacyAuthService privacyAuthService;

    @Mock
    private PrivacyMaskService privacyMaskService;

    @Mock
    private PrivacyService privacyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PrivacyController(privacyAuthService, privacyMaskService, privacyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void clearRoleContext() {
        // 清理角色 ThreadLocal：门禁用例显式 set 后防线程复用残留污染后续用例（与拦截器 afterCompletion 同口径）
        RoleContextHolder.clear();
    }

    @Test
    @DisplayName("明文查阅端点：缺 purpose 校验失败返回 400，不触达服务（明文出口守门）")
    void unmaskWithoutPurposeRejectedAs400() throws Exception {
        mockMvc.perform(post("/api/v1/patient/privacy/unmask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":5,\"fields\":[\"name\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(privacyService);
    }

    @Test
    @DisplayName("脱敏规则词表外 maskPattern 更新：@Valid 契约 400（终审 Minor 收口，不落库不生效）")
    void updateRuleRejectsUnknownMaskPatternViaContractValidation() throws Exception {
        mockMvc.perform(put("/api/v1/patient/privacy-mask-rules/MOBILE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maskPattern\":\"DROP_ALL\"}"))
                .andExpect(status().isBadRequest());
        // 词表外值在 @Valid 前置即拒：不触达服务（不落库、引擎不会被未知策略污染）
        verifyNoInteractions(privacyMaskService);
    }

    @Test
    @DisplayName("SEC-01：非 ADMIN 角色维护脱敏规则 403 PAT-1024，不触达服务（阻断自授豁免提权链）")
    void updateRuleRejectedAs403ForNonAdminRole() throws Exception {
        // 业务角色（非管理员）试图改写规则：门禁 403 前置，服务零触达（exemptRoles 不可被污染）
        RoleContextHolder.set(List.of("DOCTOR"));

        String body = mockMvc.perform(put("/api/v1/patient/privacy-mask-rules/ID_CARD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exemptRoles\":\"DOCTOR\"}"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("PAT-1024");
        verifyNoInteractions(privacyMaskService);
    }

    @Test
    @DisplayName("SEC-01：无角色上下文（空角色清单）维护脱敏规则同样 403（拦截器未注入/无角色一并不放行）")
    void updateRuleRejectedAs403ForEmptyRoles() throws Exception {
        // 空角色=非 ADMIN：显式置空清单覆盖「未设置」路径（get 回退空清单，门禁语义一致）
        RoleContextHolder.set(List.of());

        mockMvc.perform(put("/api/v1/patient/privacy-mask-rules/ID_CARD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(privacyMaskService);
    }

    @Test
    @DisplayName("SEC-01：ADMIN 角色维护脱敏规则 200 委托服务直出（管理员正常维护通道保持）")
    void updateRuleDelegatesForAdminRole() throws Exception {
        RoleContextHolder.set(List.of("ADMIN", "DOCTOR"));
        when(privacyMaskService.updateRule(any(), any()))
                .thenReturn(new PrivacyMaskRuleVO("ID_CARD", "idCardNo", "KEEP_6_4", List.of("DOCTOR"), true));

        String body = mockMvc.perform(put("/api/v1/patient/privacy-mask-rules/ID_CARD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exemptRoles\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"ruleCode\":\"ID_CARD\"")
                .contains("\"exemptRoles\":[\"DOCTOR\"]");
        verify(privacyMaskService).updateRule(any(), any());
    }

    @Test
    @DisplayName("SEC-01 行为保持：非 ADMIN 读脱敏规则清单 200（只收写端点，读端点不设门禁）")
    void maskRulesReadableForNonAdminRole() throws Exception {
        RoleContextHolder.set(List.of("NURSE"));
        when(privacyMaskService.listRules())
                .thenReturn(List.of(new PrivacyMaskRuleVO("MOBILE", "mobile", "KEEP_3_4", List.of(), true)));

        String body = mockMvc.perform(get("/api/v1/patient/privacy-mask-rules"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"ruleCode\":\"MOBILE\"");
        verify(privacyMaskService).listRules();
    }

    @Test
    @DisplayName("授权登记 signedAtIso 非 ISO 时刻：400 PAT-1023（D-15 收口，不走全局 500）")
    void createAuthWithMalformedSignedAtIsoRejectedAsPat1023() throws Exception {
        String body = mockMvc.perform(post("/api/v1/patient/privacy-auths")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":5,\"authType\":\"SENSITIVE_USE\",\"authBasis\":\"凭据-001\","
                                + "\"signedAtIso\":\"2026-13-40 08:00\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // ProblemDetail 契约：errorCode 扩展属性承载业务错误码（与生产行为一致）
        assertThat(body).contains("PAT-1023");
        verifyNoInteractions(privacyAuthService);
    }

    @Test
    @DisplayName("明文查阅端点：委托服务直出明文值集（200）")
    void unmaskDelegatesAndReturnsPlaintextValues() throws Exception {
        when(privacyService.unmask(any())).thenReturn(new UnmaskVO(5L, Map.of("name", "张三")));

        String body = mockMvc.perform(post("/api/v1/patient/privacy/unmask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":5,\"fields\":[\"name\"],\"purpose\":\"临床核验\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"patientId\":5").contains("\"values\":{\"name\":\"张三\"}");
        verify(privacyService).unmask(any());
    }

    @Test
    @DisplayName("查阅台账端点：委托服务直出分页（200，0 基页码与总条数契约）")
    void accessLogsDelegatesAndReturnsPagedLedger() throws Exception {
        when(privacyService.listAccessLogs(5L, 0, 20))
                .thenReturn(PageResult.of(
                        List.of(new PrivacyAccessLogVO(
                                66L,
                                "op-001",
                                5L,
                                "UNMASK_QUERY",
                                "临床核验",
                                "idCardNo",
                                OffsetDateTime.parse("2026-09-16T10:15:00+08:00"),
                                "t-123")),
                        0,
                        20,
                        1));

        String body = mockMvc.perform(get("/api/v1/patient/privacy-access-logs").param("patientId", "5"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .contains("\"total\":1")
                .contains("\"operatorId\":\"op-001\"")
                .contains("\"accessType\":\"UNMASK_QUERY\"");
        verify(privacyService).listAccessLogs(5L, 0, 20);
    }
}
