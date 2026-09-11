package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.api.TokenVerifier;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

/**
 * STOMP 握手鉴权拦截器单元测试（BRIEF-PR4-01 §4：通过/拒绝两态 + Bearer 前缀格式分支）。
 *
 * <p>覆盖：合法 Bearer 令牌放行（令牌全链校验委托 TokenVerifier）、令牌校验未过 401 拒绝、
 * Authorization 头缺失 401（且不触达校验器——无令牌无需进校验链）、非 Bearer 方案 401、
 * "Bearer 空串"视同缺失 401。拒绝路径必须置 401 状态码（握手失败），日志不含令牌值（红线 6）。
 * 真实容器握手链路（Tomcat + 标准客户端带 Authorization 头）归 IotTelemetryPipelineIT 步骤 3/5。
 */
@ExtendWith(MockitoExtension.class)
class StompHandshakeAuthInterceptorTest {

    /** 测试令牌样本（无意义假值，仅具单测意义；真实令牌经 M01 登录签发） */
    private static final String VALID_TOKEN = "it-fake-access-token";

    /** 握手请求 URI（warn 日志留痕断言无关，仅构造真实请求形态） */
    private static final URI HANDSHAKE_URI = URI.create("ws://localhost:8080/ws/iot");

    @Mock
    private TokenVerifier tokenVerifier;

    @Mock
    private ServerHttpRequest request;

    @Mock
    private ServerHttpResponse response;

    @Mock
    private WebSocketHandler wsHandler;

    @Captor
    private ArgumentCaptor<HttpStatus> statusCaptor;

    private final Map<String, Object> attributes = new HashMap<>();

    private StompHandshakeAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompHandshakeAuthInterceptor(tokenVerifier);
    }

    @Test
    @DisplayName("合法 Bearer 令牌：TokenVerifier 校验通过放行握手（不置错误状态码）")
    void allowsHandshakeWhenBearerTokenVerifies() {
        mockRequestHeaders("Bearer " + VALID_TOKEN);
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(true);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(allowed).as("校验通过放行握手").isTrue();
        verify(response, never()).setStatusCode(any());
    }

    @Test
    @DisplayName("令牌校验未通过：401 拒绝握手（拒绝不区分原因防枚举）")
    void rejectsHandshakeWithUnauthorizedWhenTokenFailsVerification() {
        mockRequestHeaders("Bearer " + VALID_TOKEN);
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(false);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Authorization 头缺失：401 拒绝且不触达 TokenVerifier（无令牌无需进校验链）")
    void rejectsHandshakeWithoutAuthorizationHeaderBeforeVerification() {
        mockRequestHeaders(null);

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("非 Bearer 方案（如 Basic）：401 拒绝且不触达 TokenVerifier")
    void rejectsHandshakeWithNonBearerScheme() {
        mockRequestHeaders("Basic aXQtdXNlcjppdC1wYXNz");

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("Bearer 后空串：视同令牌缺失，401 拒绝且不触达 TokenVerifier")
    void rejectsHandshakeWhenBearerValueIsBlank() {
        mockRequestHeaders("Bearer ");

        boolean allowed = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(allowed).isFalse();
        verify(response).setStatusCode(statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    /** 构造握手请求头载体（Authorization 可空模拟缺失）并固定 URI 供日志留痕取值 */
    private void mockRequestHeaders(String authorizationHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (authorizationHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(HANDSHAKE_URI);
    }
}
