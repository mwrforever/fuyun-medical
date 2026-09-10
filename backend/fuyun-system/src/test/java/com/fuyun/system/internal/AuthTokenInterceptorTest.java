package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.record.SessionData;
import com.fuyun.system.service.ITokenService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 认证拦截器 401 契约测试（BRIEF-PR3-01 §1.3/§1.4）。
 *
 * <p>覆盖：缺 Authorization 头 / 非 Bearer 前缀 / 令牌校验失败（含 SYS-1004 过期）→ 拦截器直接写出
 * 401 application/problem+json（结构 {type,title,status,detail,errorCode,traceId}，不经
 * GlobalExceptionHandler——拦截器无异常出口）；校验通过 → OperatorContextHolder 注入操作人并放行；
 * 请求收尾 → 操作人上下文必清理。MDC traceId 由 TraceIdFilter（HIGHEST_PRECEDENCE）先于本拦截器建立，
 * 测试以手工置入 MDC 模拟该前提。
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenInterceptorTest {

    /** 测试用 traceId：模拟 TraceIdFilter 置入 MDC 的追踪锚点 */
    private static final String TRACE_ID = "it-trace-anchor";

    @Mock
    private ITokenService tokenService;

    private AuthTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthTokenInterceptor(tokenService, new ObjectMapper());
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        // 模拟 Filter 链收尾清理 + 操作人上下文清理，防线程复用串号影响后续用例
        MDC.remove("traceId");
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("缺 Authorization 头：写出 401 ProblemDetail（SYS-1003 + traceId）并拦截请求")
    void missingAuthorizationHeaderWrites401ProblemDetail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/dicts/gender");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertUnauthorizedProblemDetail(response, SystemErrorCode.TOKEN_MISSING_OR_INVALID, "401");
    }

    @Test
    @DisplayName("非 Bearer 前缀：写出 401 ProblemDetail 并拦截请求")
    void nonBearerSchemeWrites401ProblemDetail() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/dicts/gender");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertUnauthorizedProblemDetail(response, SystemErrorCode.TOKEN_MISSING_OR_INVALID, "401");
    }

    @Test
    @DisplayName("校验通过：注入操作人上下文（userId 十进制字符串）并放行")
    void validTokenSetsOperatorContextAndProceeds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/dicts/gender");
        request.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.verify("good-token", "access"))
                .thenReturn(new SessionData(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN")));

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(OperatorContextHolder.get()).isEqualTo("123");
    }

    @Test
    @DisplayName("令牌已过期：tokenService 抛 SYS-1004，拦截器原样转写 401 ProblemDetail")
    void expiredTokenWrites401WithExpiredCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/dicts/gender");
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenService.verify("expired-token", "access"))
                .thenThrow(new BizException(SystemErrorCode.TOKEN_EXPIRED, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"));

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isFalse();
        assertUnauthorizedProblemDetail(response, SystemErrorCode.TOKEN_EXPIRED, "登录已过期，请重新登录");
    }

    @Test
    @DisplayName("请求收尾：afterCompletion 清理操作人上下文（finally 语义，防线程复用串号）")
    void afterCompletionClearsOperatorContext() {
        OperatorContextHolder.set("123");

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertThat(OperatorContextHolder.get()).isNull();
    }

    /** 断言 401 响应体与全局渲染同构：contentType/status/title/errorCode/traceId/detail 全字段 */
    private void assertUnauthorizedProblemDetail(
            HttpServletResponse response, SystemErrorCode expectedCode, String expectedDetailPart) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).contains("application/problem+json");
        String body = ((MockHttpServletResponse) response).getContentAsString();
        assertThat(body)
                .contains("\"title\":\"Unauthorized\"")
                .contains("\"status\":401")
                .contains("\"errorCode\":\"" + expectedCode.getCode() + "\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"")
                .contains(expectedDetailPart);
    }
}
