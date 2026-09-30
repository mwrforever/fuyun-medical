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
import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.dto.PrivacyAccessLogQuery;
import com.fuyun.patient.service.IPrivacyAuthService;
import com.fuyun.patient.service.IPrivacyMaskService;
import com.fuyun.patient.service.IPrivacyService;
import com.fuyun.patient.vo.PrivacyAccessLogVO;
import com.fuyun.patient.vo.PrivacyAuthVO;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 隐私端点薄层单测（M02 Spec §7）：unmask 缺 purpose 400 校验拒绝、unmask 200 值集委托直出与
 * 台账分页 200（controller 禁业务逻辑与事务，豁免/落痕归 service 单测；SENSITIVE_QUERY/WRITE
 * 审计落点由切面承载，standalone 骨架不加载）；授权登记/清单端点委托 service 直出（实体组装/
 * 时刻派生/落库/PAT-1023 解析守卫语义归 PrivacyAuthServiceImplTest）。
 *
 * <p>SEC-01 安全收口：脱敏规则维护 ADMIN 门禁（非 ADMIN 403 PAT-1024 且规则行零触达）已随
 * 业务逻辑下沉 PrivacyMaskServiceImplTest 承载；本类保留 HTTP 错误契约锚点——service 侧
 * PAT-1024 异常经全局渲染器出 403，及读端点（规则清单）无 ADMIN 门禁行为保持。
 */
@ExtendWith(MockitoExtension.class)
class PrivacyControllerTest {

    @Mock
    private IPrivacyAuthService privacyAuthService;

    @Mock
    private IPrivacyMaskService privacyMaskService;

    @Mock
    private IPrivacyService privacyService;

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
    @DisplayName("SEC-01 契约保持：service 侧 PAT-1024 门禁异常经全局渲染器出 403（门禁已下沉 service）")
    void updateRuleRendersServiceSidePat1024As403() throws Exception {
        // 门禁拒绝语义（非 ADMIN 403 + 规则行零触达）归 PrivacyMaskServiceImplTest；本用例锚定
        // service 抛出的 PAT-1024 经全局渲染器仍出 403 ProblemDetail（HTTP 错误契约不变）
        when(privacyMaskService.updateRule(any(), any()))
                .thenThrow(new BizException(
                        PatientErrorCode.PRIVACY_RULE_MAINTENANCE_FORBIDDEN, HttpStatus.FORBIDDEN, "脱敏规则维护仅限系统管理员"));

        String body = mockMvc.perform(put("/api/v1/patient/privacy-mask-rules/ID_CARD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exemptRoles\":\"DOCTOR\"}"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // ProblemDetail 契约：errorCode 扩展属性承载业务错误码（门禁下沉后 403 语义逐字保持）
        assertThat(body).contains("PAT-1024");
        verify(privacyMaskService).updateRule(any(), any());
    }

    @Test
    @DisplayName("规则维护端点：委托服务直出维护后规则（200，薄层透传）")
    void updateRuleDelegatesAndReturnsUpdatedRule() throws Exception {
        when(privacyMaskService.updateRule(any(), any()))
                .thenReturn(new PrivacyMaskRuleVO("ID_CARD", "idCardNo", "KEEP_6_4", List.of("DOCTOR"), true));

        String body = mockMvc.perform(put("/api/v1/patient/privacy-mask-rules/ID_CARD")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exemptRoles\":\"DOCTOR\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"ruleCode\":\"ID_CARD\"").contains("\"exemptRoles\":[\"DOCTOR\"]");
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
    @DisplayName("授权清单端点：委托服务直出出参清单（200，派生状态已组装）")
    void authsDelegatesAndReturnsAuthVos() throws Exception {
        when(privacyAuthService.listAuthVosByPatient(5L))
                .thenReturn(List.of(new PrivacyAuthVO(
                        66L,
                        5L,
                        "SENSITIVE_USE",
                        "凭据-001",
                        "科研利用",
                        OffsetDateTime.parse("2026-09-01T10:00:00+08:00"),
                        OffsetDateTime.parse("2027-09-01T10:00:00+08:00"),
                        "EFFECTIVE")));

        String body = mockMvc.perform(get("/api/v1/patient/privacy-auths").param("patientId", "5"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"id\":66").contains("\"derivedStatus\":\"EFFECTIVE\"");
        verify(privacyAuthService).listAuthVosByPatient(5L);
    }

    @Test
    @DisplayName("授权登记端点：委托服务直出授权出参（201，薄层透传）")
    void createAuthDelegatesAndReturnsAuthVo() throws Exception {
        when(privacyAuthService.createAuth(any()))
                .thenReturn(new PrivacyAuthVO(
                        66L,
                        5L,
                        "SENSITIVE_USE",
                        "凭据-001",
                        "科研利用",
                        OffsetDateTime.parse("2026-09-01T10:00:00+08:00"),
                        OffsetDateTime.parse("2027-09-01T10:00:00+08:00"),
                        "EFFECTIVE"));

        String body = mockMvc.perform(post("/api/v1/patient/privacy-auths")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":5,\"authType\":\"SENSITIVE_USE\",\"authBasis\":\"凭据-001\","
                                + "\"signedAtIso\":\"2026-09-01T10:00:00+08:00\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("\"id\":66").contains("\"derivedStatus\":\"EFFECTIVE\"");
        verify(privacyAuthService).createAuth(any());
    }

    @Test
    @DisplayName("授权登记端点：服务侧 PAT-1023 守卫异常经全局渲染器出 400（D-15 契约保持，解析语义在 service）")
    void createAuthRendersServiceSidePat1023As400() throws Exception {
        when(privacyAuthService.createAuth(any()))
                .thenThrow(new BizException(
                        PatientErrorCode.PARAM_FORMAT_INVALID,
                        HttpStatus.BAD_REQUEST,
                        "signedAtIso 须为合法 ISO-8601 时刻（如 2026-09-17T10:15:00+08:00）"));

        String body = mockMvc.perform(post("/api/v1/patient/privacy-auths")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":5,\"authType\":\"SENSITIVE_USE\",\"authBasis\":\"凭据-001\","
                                + "\"signedAtIso\":\"2026-13-40 08:00\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // ProblemDetail 契约：errorCode 扩展属性承载业务错误码（守卫已下沉 service，HTTP 错误契约不变）
        assertThat(body).contains("PAT-1023");
        verify(privacyAuthService).createAuth(any());
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
    @DisplayName("查阅台账端点：委托服务直出分页（200，0 基页码与总条数契约，原始参数对象直传）")
    void accessLogsDelegatesAndReturnsPagedLedger() throws Exception {
        // 检索条件以参数对象直传（缺省 page=0/size=20），size 收敛语义归 PrivacyServiceImplTest
        PrivacyAccessLogQuery query = new PrivacyAccessLogQuery(5L, 0, 20);
        when(privacyService.listAccessLogs(query))
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
        verify(privacyService).listAccessLogs(query);
    }
}
