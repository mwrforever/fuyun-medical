package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.constants.SecurityConstants;
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
 * 认证拦截器 401 契约测试（BRIEF-PR3-01 §1.3/§1.4）+ 哨兵 REST 限行 403 契约（PR-4C W-39）。
 *
 * <p>401 覆盖：缺 Authorization 头 / 非 Bearer 前缀 / 令牌校验失败（含 SYS-1004 过期）→ 拦截器直接写出
 * 401 application/problem+json（结构 {type,title,status,detail,errorCode,traceId}，不经
 * GlobalExceptionHandler——拦截器无异常出口）；校验通过 → OperatorContextHolder 注入操作人并放行；
 * 请求收尾 → 操作人上下文必清理。MDC traceId 由 TraceIdFilter（HIGHEST_PRECEDENCE）先于本拦截器建立，
 * 测试以手工置入 MDC 模拟该前提。
 *
 * <p>哨兵限行 403 覆盖（W-39/A-1）：大屏匿名令牌（loginName=bigscreen）仅放行三 allowlist 端点
 * （board/infusion-board 前缀+尾段 wardId 一致、iot/alarms 精确+query wardId 一致），
 * 越区/越面/泛哨兵（wardId=null）一律 403 SYS-1032；登录态会话零影响（回归锚）。
 */
@ExtendWith(MockitoExtension.class)
class AuthTokenInterceptorTest {

    /** 测试用 traceId：模拟 TraceIdFilter 置入 MDC 的追踪锚点 */
    private static final String TRACE_ID = "it-trace-anchor";

    /** 哨兵限行用例的令牌原文：verify 桩锚点（断言与日志均不涉令牌内容） */
    private static final String RAW = "sentinel-raw-token";

    /** 哨兵登录名：与生产 SecurityConstants 公共常量同源（判定锚点不自造字面量） */
    private static final String BIGSCREEN_LOGIN = SecurityConstants.BIGSCREEN_LOGIN_NAME;

    @Mock
    private ITokenService tokenService;

    private AuthTokenInterceptor interceptor;

    /** 哨兵限行用例的响应载体：类字段承载（JUnit 每方法新实例），preHandle 传入后 verifyForbidden 统一断言 */
    private MockHttpServletResponse response = new MockHttpServletResponse();

    /** 目标处理器占位：拦截器不区分 handler 类型，统一传 Object */
    private final Object handler = new Object();

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
                .thenReturn(new SessionData(123L, "admin", "系统管理员", 456L, null, List.of("ADMIN"), null, List.of()));

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

    @Test
    @DisplayName("哨兵+board 路径尾段与会话病区一致：放行（W-68 三端点 200 的机制面）")
    void sentinelAllowedWhenBoardPathMatchesSessionWard() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
                .thenReturn(session(BIGSCREEN_LOGIN, "1001"));

        assertThat(interceptor.preHandle(request("GET", "/api/v1/nursing/board/1001"), response, handler))
                .isTrue();
    }

    @Test
    @DisplayName("哨兵+board 路径尾段与会话病区不一致：403 SYS-1032（A-2 HTTP 侧面越区拒绝）")
    void sentinelRejectedWhenBoardPathWardMismatches() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
                .thenReturn(session(BIGSCREEN_LOGIN, "1001"));

        assertThat(interceptor.preHandle(request("GET", "/api/v1/nursing/board/W02"), response, handler))
                .isFalse();
        verifyForbidden(SystemErrorCode.SENTINEL_ACCESS_DENIED);
    }

    @Test
    @DisplayName("哨兵调写面（任意非 allowlist 路径）：403 SYS-1032（W-39/A-1 收敛主体面）")
    void sentinelRejectedWhenAccessingNonAllowlistedEndpoint() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
                .thenReturn(session(BIGSCREEN_LOGIN, "1001"));

        assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response, handler))
                .isFalse();
        verifyForbidden(SystemErrorCode.SENTINEL_ACCESS_DENIED);
        // 403 分支手动清理效果断言：preHandle 返回 false 时 Spring 不回调本拦截器 afterCompletion，
        // 已注入的操作人/角色上下文须在拒绝分支内清理完毕（防线程复用串号；角色上下文空态归一空清单）
        assertThat(OperatorContextHolder.get()).isNull();
        assertThat(RoleContextHolder.get()).isEmpty();
    }

    @Test
    @DisplayName("泛哨兵（wardId=null）访问 allowlist 端点也拒：无一致可校一律 fail-closed")
    void sentinelWithoutWardRejectedEvenOnAllowlistedEndpoint() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS)).thenReturn(session(BIGSCREEN_LOGIN, null));

        assertThat(interceptor.preHandle(request("GET", "/api/v1/nursing/board/1001"), response, handler))
                .isFalse();
    }

    @Test
    @DisplayName("哨兵+iot alarms query wardId 与会话病区等值：放行（精确端点 query 一致性）")
    void sentinelAllowedWhenIotAlarmsQueryWardMatchesSession() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
                .thenReturn(session(BIGSCREEN_LOGIN, "1001"));
        MockHttpServletRequest request = request("GET", "/api/v1/iot/alarms");
        request.addParameter("wardId", "1001");

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
    }

    @Test
    @DisplayName("哨兵+iot alarms query wardId 不等值：403 SYS-1032（精确端点 query 一致性）")
    void sentinelRejectedWhenIotAlarmsQueryWardMismatches() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS))
                .thenReturn(session(BIGSCREEN_LOGIN, "1001"));
        MockHttpServletRequest request = request("GET", "/api/v1/iot/alarms");
        request.addParameter("wardId", "1002");

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        verifyForbidden(SystemErrorCode.SENTINEL_ACCESS_DENIED);
    }

    @Test
    @DisplayName("登录态会话不受哨兵限行影响：admin 任意路径照旧放行（回归锚）")
    void loginSessionUnaffectedBySentinelGuard() throws Exception {
        when(tokenService.verify(RAW, SecurityConstants.TOKEN_TYPE_ACCESS)).thenReturn(session("admin", null));

        assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response, handler))
                .isTrue();
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

    /**
     * 构造哨兵/登录会话样本（verify 桩返回值）。
     *
     * @param loginName 登录名，非空；"bigscreen"=哨兵会话（触发限行判定），其他值=登录态
     * @param wardId    会话绑定病区编码，可空；null=泛哨兵（登录态样本同样传 null）
     * @return 会话数据（userId=0 哨兵语义；verify 已 mock，userId 不参与放行/拒绝断言）
     */
    private SessionData session(String loginName, String wardId) {
        return new SessionData(0L, loginName, "候诊大屏", null, null, List.of(), wardId, List.of());
    }

    /**
     * 构造携带 Bearer 令牌头的请求。
     *
     * @param method HTTP 方法，非空
     * @param uri    请求 URI，非空；尾段/前缀即限行判定的比对目标
     * @return 已注入 Authorization 头的 mock 请求
     */
    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(SecurityConstants.AUTH_HEADER, SecurityConstants.BEARER_PREFIX + RAW);
        return request;
    }

    /** 断言 403 响应体与 401 同构（contentType/status/title/errorCode/traceId 全字段） */
    private void verifyForbidden(SystemErrorCode expectedCode) throws Exception {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"title\":\"Forbidden\"")
                .contains("\"status\":403")
                .contains("\"errorCode\":\"" + expectedCode.getCode() + "\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"");
    }
}
