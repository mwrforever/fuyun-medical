package com.fuyun.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.common.web.PageResult;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.BindDeviceRequest;
import com.fuyun.iot.dto.BindingQueryRequest;
import com.fuyun.iot.dto.UnbindDeviceRequest;
import com.fuyun.iot.enums.BindType;
import com.fuyun.iot.enums.BindingStatus;
import com.fuyun.iot.service.IBindingService;
import com.fuyun.iot.vo.BindingVO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 绑定管理端点薄层冒烟单测（五端点：绑定 201 / 解绑 204 / 分页 / 病区清单 / 生效绑定查询；
 * JaCoCo BUNDLE 兜底）：controller 禁业务逻辑与事务（A.1-8），校验链与状态机守卫归服务层
 * （BindingServiceImplTest 承载）；@Valid 400 与 BizException ProblemDetail 经全局渲染器按
 * 生产行为出网。挂 WRITE 审计注解的落账行为归 M01 审计切面，不在本薄层断言面。
 */
@ExtendWith(MockitoExtension.class)
class BindingControllerTest {

    private static final String DEVICE_ID = "fuyun-demo-001";

    private static final String VISIT_ID = "I2026090100001";

    @Mock
    private IBindingService bindingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 注册全局异常渲染器：@Valid 校验失败与 BizException 按生产行为出 ProblemDetail
        mockMvc = MockMvcBuilders.standaloneSetup(new BindingController(bindingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("绑定端点：请求体透传服务层并回显落库视图（201）")
    void bindEndpointPassesRequestThroughAndReturnsCreatedVo() throws Exception {
        when(bindingService.bind(any(BindDeviceRequest.class))).thenReturn(vo());

        mockMvc.perform(post("/api/v1/iot/bindings")
                        .contentType("application/json")
                        .content("""
                            {"deviceId":"fuyun-demo-001","patientId":7,"visitId":"I2026090100001",
                             "bedId":2001,"wardId":1001,"bindType":"FIXED","bindReason":"入院固定绑定"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$.visitId").value(VISIT_ID))
                .andExpect(jsonPath("$.status").value("BOUND"));

        ArgumentCaptor<BindDeviceRequest> captor = ArgumentCaptor.forClass(BindDeviceRequest.class);
        verify(bindingService).bind(captor.capture());
        assertThatRequestMatches(captor.getValue());
    }

    @Test
    @DisplayName("绑定端点：缺 deviceId 触发 @Valid 400，服务层零触碰")
    void bindEndpointRejectsBlankDeviceIdWith400() throws Exception {
        mockMvc.perform(post("/api/v1/iot/bindings")
                        .contentType("application/json")
                        .content("""
                            {"patientId":7,"visitId":"I2026090100001","wardId":1001,"bindType":"FIXED"}
                            """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bindingService);
    }

    @Test
    @DisplayName("解绑端点：原因透传服务层并回 204")
    void unbindEndpointPassesReasonThroughAndReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/iot/bindings/{deviceId}/unbind", DEVICE_ID)
                        .contentType("application/json")
                        .content("{\"reason\":\"转床消毒\"}"))
                .andExpect(status().isNoContent());

        verify(bindingService).unbind(DEVICE_ID, new UnbindDeviceRequest("转床消毒"));
    }

    @Test
    @DisplayName("分页端点：查询参数绑定请求载体并回显分页出参（page 0 基）")
    void pageEndpointBindsQueryRequestAndReturnsPageResult() throws Exception {
        when(bindingService.page(any(BindingQueryRequest.class))).thenReturn(PageResult.of(List.of(vo()), 0, 20, 1));

        mockMvc.perform(get("/api/v1/iot/bindings")
                        .param("page", "0")
                        .param("size", "20")
                        .param("wardId", "1001")
                        .param("status", "BOUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.content[0].deviceId").value(DEVICE_ID));

        ArgumentCaptor<BindingQueryRequest> captor = ArgumentCaptor.forClass(BindingQueryRequest.class);
        verify(bindingService).page(captor.capture());
        assertThat(captor.getValue().wardId()).isEqualTo(1001L);
        assertThat(captor.getValue().status()).isEqualTo(BindingStatus.BOUND);
    }

    @Test
    @DisplayName("病区清单端点：按路径病区 id 透传并回显 BOUND 绑定数组")
    void listByWardEndpointReturnsWardBindings() throws Exception {
        when(bindingService.listByWard(1001L)).thenReturn(List.of(vo()));

        mockMvc.perform(get("/api/v1/iot/bindings/wards/{wardId}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value(DEVICE_ID))
                .andExpect(jsonPath("$[0].wardId").value(1001));

        verify(bindingService).listByWard(1001L);
    }

    @Test
    @DisplayName("生效绑定端点：有绑定回 200 视图")
    void activeBindingEndpointReturnsVoWhenPresent() throws Exception {
        when(bindingService.findActiveByDevice(DEVICE_ID)).thenReturn(Optional.of(vo()));

        mockMvc.perform(get("/api/v1/iot/bindings/devices/{deviceId}/active", DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID));
    }

    @Test
    @DisplayName("生效绑定端点：无绑定回 404 ProblemDetail（IOT-1009）")
    void activeBindingEndpointReturns404WhenAbsent() throws Exception {
        when(bindingService.findActiveByDevice(DEVICE_ID)).thenReturn(Optional.empty());

        String body = mockMvc.perform(get("/api/v1/iot/bindings/devices/{deviceId}/active", DEVICE_ID))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains(IotErrorCode.BINDING_NOT_FOUND.getCode());
        assertThat(body).contains(String.valueOf(HttpStatus.NOT_FOUND.value()));
    }

    /** 构造生效绑定视图（JSON 出网形态断言数据源） */
    private static BindingVO vo() {
        return new BindingVO(
                9001L,
                DEVICE_ID,
                70L,
                VISIT_ID,
                2001L,
                1001L,
                BindType.FIXED,
                BindingStatus.BOUND,
                "入院固定绑定",
                null,
                "E1001",
                null,
                null);
    }

    /** 断言绑定请求体经 JSON 反序列化后字段完整到达服务层边界 */
    private static void assertThatRequestMatches(BindDeviceRequest request) {
        assertThat(request.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(request.patientId()).isEqualTo(7L);
        assertThat(request.visitId()).isEqualTo(VISIT_ID);
        assertThat(request.bedId()).isEqualTo(2001L);
        assertThat(request.wardId()).isEqualTo(1001L);
        assertThat(request.bindType()).isEqualTo(BindType.FIXED);
        assertThat(request.bindReason()).isEqualTo("入院固定绑定");
    }
}
