package com.fuyun.outpatient.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import com.fuyun.outpatient.service.IClinicOrderService;
import com.fuyun.outpatient.service.IVisitService;
import com.fuyun.outpatient.vo.DoctorQueueItemVO;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
 * 门诊医生站薄层单测（M03 Spec §7）：SEC-02 安全收口用例——候诊列表 doctorId 与会话身份比对
 * 门禁。doctorId 语义为「当前登录医生自身」（workstation DoctorStationView 传 auth.user.userId，
 * 会话 userId 经认证拦截器以十进制串注入 OperatorContextHolder）：
 * 不一致 403 OP-1020 不触达服务 / 一致 200 委托直出 / 无会话身份（系统态调用）放行委托。
 * 其余端点（接诊/诊毕/开单）业务语义归 service 单测，controller 层不重复覆盖。
 */
@ExtendWith(MockitoExtension.class)
class VisitControllerTest {

    @Mock
    private IVisitService visitService;

    @Mock
    private IClinicOrderService clinicOrderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：BizException 按自带 HttpStatus 出 ProblemDetail（与生产行为一致）
        mockMvc = MockMvcBuilders.standaloneSetup(new VisitController(visitService, clinicOrderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void clearOperatorContext() {
        // 清理操作人 ThreadLocal：门禁用例显式 set 后防线程复用残留污染后续用例（与拦截器 afterCompletion 同口径）
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("SEC-02：doctorId 与会话身份不一致 403 OP-1020，不触达服务（阻断横向窥看他医生队列）")
    void patientQueueRejectedAs403WhenDoctorIdMismatchesSession() throws Exception {
        // 会话登录者为 9001，却请求 8888 的候诊队列：IDOR 横向越权，门禁 403 前置
        OperatorContextHolder.set("9001");

        String body = mockMvc.perform(get("/api/v1/outpatient/doctor/patient-queue")
                        .param("deptCode", "DEP001")
                        .param("doctorId", "8888"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("OP-1020");
        verifyNoInteractions(visitService);
    }

    @Test
    @DisplayName("SEC-02：doctorId 与会话身份一致 200 委托服务直出（本人候诊队列正常通道保持）")
    void patientQueueDelegatesWhenDoctorIdMatchesSession() throws Exception {
        OperatorContextHolder.set("9001");
        when(visitService.patientQueue("DEP001", "9001"))
                .thenReturn(List.of(new DoctorQueueItemVO(
                        501L,
                        "O2026092400001",
                        "A001",
                        "张*",
                        TicketType.FIRST,
                        100,
                        TicketStatus.WAITING,
                        false,
                        OffsetDateTime.parse("2026-09-24T08:00:00+08:00"),
                        0)));

        String body = mockMvc.perform(get("/api/v1/outpatient/doctor/patient-queue")
                        .param("deptCode", "DEP001")
                        .param("doctorId", "9001"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("A001");
        verify(visitService).patientQueue("DEP001", "9001");
    }

    @Test
    @DisplayName("SEC-02：无会话身份（系统态调用）放行委托服务（无可比对象，保持既有内部链路可用）")
    void patientQueueDelegatesWhenSessionIdentityAbsent() throws Exception {
        // 不设 OperatorContextHolder：模拟系统态/无登录上下文调用（生产行请求经认证拦截器恒有身份）
        when(visitService.patientQueue("DEP001", "9001")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/outpatient/doctor/patient-queue")
                        .param("deptCode", "DEP001")
                        .param("doctorId", "9001"))
                .andExpect(status().isOk());

        verify(visitService).patientQueue("DEP001", "9001");
    }
}
