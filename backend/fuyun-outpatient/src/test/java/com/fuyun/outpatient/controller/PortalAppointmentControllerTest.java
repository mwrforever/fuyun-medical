package com.fuyun.outpatient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.outpatient.api.OutpatientErrorCode;
import com.fuyun.outpatient.cache.PortalCredentialRateGuard;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.enums.ApptChannel;
import com.fuyun.outpatient.enums.ApptStatus;
import com.fuyun.outpatient.enums.FeeStatusType;
import com.fuyun.outpatient.service.IAppointmentService;
import com.fuyun.outpatient.service.IScheduleService;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.patient.api.PatientErrorCode;
import com.fuyun.patient.api.PatientIdentityQuery;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * portal 匿名预约控制器薄层单测（M03 Spec §7 裁决 13 免登录通道）：BUG-01 收口用例——免登录
 * 退号端点介质归属校验门禁。单号 AP+日期+顺序流水高度可枚举，匿名请求禁仅凭单号触达退号主流程：
 * 介质凭证缺失/词表外 400 OP-1019 前置拒绝（不触达解析与服务）；介质未命中 PAT-1001 透出；
 * 解析成功后必须携归属患者主索引委托服务层三参 cancel（比对语义归 service 单测，本层只验证
 * 传参与异常透传）。book 端点业务语义归 service 单测，controller 层不重复覆盖。
 *
 * <p>EX-29 临时缓解②（BE-A3-02，裁决③）追加 book 链路频控编排用例：守卫取真实实例 + mock
 * Redis（判定本体真实执行、Redis 面可控模拟）——冷却期前置 429 OP-1023（不触达解析与预约）、
 * 解析失败计数（连续失败语义）、成功清零与超限 OP-1022 透传。缓解①上限判定本体归
 * AppointmentServiceImplTest，本层只验透传形态。
 */
@ExtendWith(MockitoExtension.class)
class PortalAppointmentControllerTest {

    /** 测试用证件号（虚构样例，仅承载频控编排断言） */
    private static final String CREDENTIAL_NO = "110101199001011234";

    @Mock
    private IScheduleService scheduleService;

    @Mock
    private IAppointmentService appointmentService;

    @Mock
    private PatientIdentityQuery patientIdentityQuery;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 频控守卫取真实实例 + mock Redis：链路级验证判定本体（判定面归 PortalCredentialRateGuardTest 细测）
        PortalCredentialRateGuard credentialRateGuard = new PortalCredentialRateGuard(redisTemplate);
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new PortalAppointmentController(
                        scheduleService, appointmentService, patientIdentityQuery, credentialRateGuard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("BUG-01：免登录退号缺介质凭证 400 OP-1019，不触达介质解析与退号服务（禁裸单号退号）")
    void cancelRejectedAs400WhenCredentialMissing() throws Exception {
        String body = mockMvc.perform(post("/api/v1/outpatient/portal/appointments/AP20260928000001/cancel")
                        .contentType("application/json")
                        .content("{\"reason\":\"行程变动取消\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("OP-1019");
        verifyNoInteractions(patientIdentityQuery, appointmentService);
    }

    @Test
    @DisplayName("BUG-01：免登录退号介质类型词表外 400 OP-1019，不触达介质解析与退号服务")
    void cancelRejectedAs400WhenCredentialTypeOutOfVocabulary() throws Exception {
        String body = mockMvc.perform(
                        post("/api/v1/outpatient/portal/appointments/AP20260928000001/cancel")
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"行程变动取消\",\"credentialType\":\"PASSPORT\",\"credentialNo\":\"P1234567\"}"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("OP-1019");
        verifyNoInteractions(patientIdentityQuery, appointmentService);
    }

    @Test
    @DisplayName("BUG-01：介质未命中 PAT-1001 由 patient 契约异常透出（404），不触达退号服务")
    void cancelPropagatesPatientIdentityMiss() throws Exception {
        when(patientIdentityQuery.resolveActivePatientId(anyString(), anyString()))
                .thenThrow(new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND));

        String body = mockMvc.perform(
                        post("/api/v1/outpatient/portal/appointments/AP20260928000001/cancel")
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"行程变动取消\",\"credentialType\":\"ID_CARD\",\"credentialNo\":\"110101199001011234\"}"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("PAT-1001");
        verifyNoInteractions(appointmentService);
    }

    @Test
    @DisplayName("BUG-01：介质归属不匹配——服务层 OP-1021 403 透传（匿名越权退号被拒）")
    void cancelRejectedAs403WhenOwnershipMismatch() throws Exception {
        when(patientIdentityQuery.resolveActivePatientId("ID_CARD", "110101199001011234"))
                .thenReturn(777L);
        // 服务层比对介质解析患者 777 与单据归属患者不一致 → OP-1021（比对本体归 service 单测）
        when(appointmentService.cancel("AP20260928000001", "行程变动取消", 777L))
                .thenThrow(new BizException(OutpatientErrorCode.APPT_OWNER_MISMATCH, HttpStatus.FORBIDDEN));

        String body = mockMvc.perform(
                        post("/api/v1/outpatient/portal/appointments/AP20260928000001/cancel")
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"行程变动取消\",\"credentialType\":\"ID_CARD\",\"credentialNo\":\"110101199001011234\"}"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("OP-1021");
        verify(appointmentService).cancel("AP20260928000001", "行程变动取消", 777L);
    }

    @Test
    @DisplayName("BUG-01：介质归属匹配——携解析患者委托三参 cancel 直出（本人退号正常通道保持）")
    void cancelDelegatesWithResolvedPatientIdWhenCredentialMatches() throws Exception {
        when(patientIdentityQuery.resolveActivePatientId("VISIT_CARD", "V000900001"))
                .thenReturn(9L);
        when(appointmentService.cancel("AP20260928000001", "行程变动取消", 9L)).thenReturn(cancelledVo());

        String body = mockMvc.perform(
                        post("/api/v1/outpatient/portal/appointments/AP20260928000001/cancel")
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"行程变动取消\",\"credentialType\":\"VISIT_CARD\",\"credentialNo\":\"V000900001\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 传参断言：介质解析患者随退号委托下行（归属比对锚点），非旧两参裸单号形态
        verify(appointmentService).cancel("AP20260928000001", "行程变动取消", 9L);
        assertThat(body).contains("\"AP20260928000001\"").contains("CANCELLED");
    }

    @Test
    @DisplayName("EX-29 缓解②：频控冷却中拒绝——429 OP-1023 前置拦截，不触达介质解析与预约服务（枚举面收敛）")
    void bookRejectedAs429WhenCredentialCoolingDown() throws Exception {
        // 冷却标记在挂（键成分为摘要，argThat 只锚前缀，明文断言归守卫单测）
        when(redisTemplate.hasKey(argThat(key -> key != null && key.startsWith("fy:outpatient:portal-cred-cool:"))))
                .thenReturn(true);

        String body = mockMvc.perform(post("/api/v1/outpatient/portal/appointments")
                        .contentType("application/json")
                        .content("{\"credentialType\":\"ID_CARD\",\"credentialNo\":\"" + CREDENTIAL_NO
                                + "\",\"poolId\":31}"))
                .andExpect(status().isTooManyRequests())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("OP-1023");
        verifyNoInteractions(patientIdentityQuery, appointmentService);
    }

    @Test
    @DisplayName("EX-29 缓解②：解析未命中 PAT-1001 计入该证件号连续失败计数后原样透出（404）")
    void bookRecordsFailureCountWhenResolutionMisses() throws Exception {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(patientIdentityQuery.resolveActivePatientId("ID_CARD", CREDENTIAL_NO))
                .thenThrow(new BizException(PatientErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String body = mockMvc.perform(post("/api/v1/outpatient/portal/appointments")
                        .contentType("application/json")
                        .content("{\"credentialType\":\"ID_CARD\",\"credentialNo\":\"" + CREDENTIAL_NO
                                + "\",\"poolId\":31}"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("PAT-1001");
        // 与档案不符即计连续失败一次（Redis INCR 计数键）；预约服务零触达
        verify(valueOperations)
                .increment(argThat(key -> key != null && key.startsWith("fy:outpatient:portal-cred-fail:")));
        verifyNoInteractions(appointmentService);
    }

    @Test
    @DisplayName("EX-29 缓解②：正常预约成功——冷却不命中+解析成功清零计数+携解析患者委托统一预约主流程")
    void bookDelegatesAfterClearingFailureCountWhenResolutionSucceeds() throws Exception {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(patientIdentityQuery.resolveActivePatientId("ID_CARD", CREDENTIAL_NO))
                .thenReturn(9L);
        when(appointmentService.book(new AppointmentCreateRequest(9L, 31L, ApptChannel.PORTAL.getCode())))
                .thenReturn(reservedVo());

        String body = mockMvc.perform(post("/api/v1/outpatient/portal/appointments")
                        .contentType("application/json")
                        .content("{\"credentialType\":\"ID_CARD\",\"credentialNo\":\"" + CREDENTIAL_NO
                                + "\",\"poolId\":31}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        // 编排断言：解析成功清零连续失败计数（键成分为摘要）+ 介质解析换得 patientId 进入统一主流程
        verify(redisTemplate).delete(argThat((String key) -> key.startsWith("fy:outpatient:portal-cred-fail:")));
        verify(appointmentService).book(new AppointmentCreateRequest(9L, 31L, ApptChannel.PORTAL.getCode()));
        assertThat(body).contains("\"AP20260928000002\"").contains("RESERVED");
    }

    @Test
    @DisplayName("EX-29 缓解①：活跃预约数超上限——服务层 OP-1022 409 透传（判定本体归 service 单测）")
    void bookPropagatesActiveAppointmentLimitExceeded() throws Exception {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(patientIdentityQuery.resolveActivePatientId("ID_CARD", CREDENTIAL_NO))
                .thenReturn(9L);
        when(appointmentService.book(new AppointmentCreateRequest(9L, 31L, ApptChannel.PORTAL.getCode())))
                .thenThrow(new BizException(OutpatientErrorCode.PORTAL_APPT_LIMIT_EXCEEDED, HttpStatus.CONFLICT));

        String body = mockMvc.perform(post("/api/v1/outpatient/portal/appointments")
                        .contentType("application/json")
                        .content("{\"credentialType\":\"ID_CARD\",\"credentialNo\":\"" + CREDENTIAL_NO
                                + "\",\"poolId\":31}"))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("OP-1022");
    }

    /** 退号成功出参替身（分支 1=CANCELLED，仅承载直出断言所需字段） */
    private AppointmentVO cancelledVo() {
        return new AppointmentVO(
                101L,
                "AP20260928000001",
                9L,
                11L,
                31L,
                null,
                null,
                null,
                null,
                ApptChannel.PORTAL,
                FeeStatusType.UNPAID,
                null,
                null,
                ApptStatus.CANCELLED);
    }

    /** 预约成功出参替身（RESERVED 占位，仅承载直出断言所需字段） */
    private AppointmentVO reservedVo() {
        return new AppointmentVO(
                102L,
                "AP20260928000002",
                9L,
                11L,
                31L,
                null,
                null,
                null,
                null,
                ApptChannel.PORTAL,
                FeeStatusType.UNPAID,
                null,
                null,
                ApptStatus.RESERVED);
    }
}
