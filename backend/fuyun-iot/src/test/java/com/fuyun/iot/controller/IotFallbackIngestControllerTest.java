package com.fuyun.iot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.web.GlobalExceptionHandler;
import com.fuyun.iot.api.IotErrorCode;
import com.fuyun.iot.dto.FallbackIngestRequest;
import com.fuyun.iot.service.IFallbackIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP 兜底入库端点薄层单测（EX-15 收窄后：@Valid 校验 + service 委派 + 202 编排；
 * JaCoCo BUNDLE 兜底）：鉴权判定与 CF-7 组装归 {@link IFallbackIngestService}
 * （FallbackIngestServiceImplTest 承载）；鉴权失败 401 IOT-1001 经全局渲染器按生产行为
 * 出网，@Valid 400 同口径。测试 token 为无意义假值（测试资产，红线 6）。
 */
@ExtendWith(MockitoExtension.class)
class IotFallbackIngestControllerTest {

    /** 测试资产假密钥（仅具单测意义，与任何真实兜底凭证无关） */
    private static final String TEST_TOKEN = "unit-only-fake-fallback-token";

    @Mock
    private IFallbackIngestService fallbackIngestService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new IotFallbackIngestController(fallbackIngestService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("受理：POST 正确 token 头 + 完整载荷 → 202 accepted=true，头与载荷透传服务")
    void acceptsAndDelegatesToService() throws Exception {
        mockMvc.perform(post("/ingest/iotda-fallback")
                        .header("X-Iot-Fallback-Token", TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"dev-fallback-001","metricCode":"MDC_ECG_HEART_RATE",
                                 "value":"72","unit":"bpm","occurredAt":"2026-09-28T08:30:00Z"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true));

        // 头原文与请求体字段逐字透传（鉴权与组装归服务，controller 只透传）
        ArgumentCaptor<FallbackIngestRequest> captor = ArgumentCaptor.forClass(FallbackIngestRequest.class);
        verify(fallbackIngestService).ingestIotdaFallback(eq(TEST_TOKEN), captor.capture());
        assertThat(captor.getValue().deviceId()).isEqualTo("dev-fallback-001");
        assertThat(captor.getValue().metricCode()).isEqualTo("MDC_ECG_HEART_RATE");
        assertThat(captor.getValue().occurredAt().toString()).isEqualTo("2026-09-28T08:30:00Z");
    }

    @Test
    @DisplayName("鉴权失败：服务抛 BizException → 全局渲染 401 ProblemDetail（errorCode=IOT-1001）")
    void rendersAuthFailureAsProblemDetail() throws Exception {
        doThrow(new BizException(IotErrorCode.FALLBACK_AUTH_FAILED, HttpStatus.UNAUTHORIZED, "兜底通道鉴权失败"))
                .when(fallbackIngestService)
                .ingestIotdaFallback(eq("wrong-token"), any(FallbackIngestRequest.class));

        mockMvc.perform(post("/ingest/iotda-fallback")
                        .header("X-Iot-Fallback-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceId":"dev-fallback-001","metricCode":"MDC_ECG_HEART_RATE",
                                 "value":"72","occurredAt":"2026-09-28T08:30:00Z"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("IOT-1001"))
                .andExpect(jsonPath("$.detail").value("兜底通道鉴权失败"));
    }

    @Test
    @DisplayName("载荷校验：deviceId 缺失 → @Valid 400，服务零调用")
    void rejectsMissingDeviceId() throws Exception {
        mockMvc.perform(post("/ingest/iotda-fallback")
                        .header("X-Iot-Fallback-Token", TEST_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"metricCode":"MDC_ECG_HEART_RATE","value":"72",
                                 "occurredAt":"2026-09-28T08:30:00Z"}""")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(fallbackIngestService);
    }
}
