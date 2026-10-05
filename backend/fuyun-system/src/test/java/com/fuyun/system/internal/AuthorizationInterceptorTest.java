package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.constants.SecurityConstants;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 403 鉴权拦截器契约测试（PR-4D W-37 主体，D3/D4 裁定的可执行化）。
 *
 * <p>覆盖：ADMIN 一票放行（优先级最高，未登记/越权均放行）；命中权限点且会话角色与允许集
 * 交集非空放行；命中但角色不符 → 403 {@code application/problem+json}（结构
 * {type,title,status,detail,errorCode,traceId} 与 AuthTokenInterceptor 拒绝面同构，
 * errorCode=SYS-1033）；未登记路径放行（D4 医疗可用性优先）且同 URI 首见 warn、重复降
 * debug（评审 A-3/B-2 修复环）；哨兵（角色空集）未登记放行/已登记拒绝双面。角色上下文经
 * RoleContextHolder set/clear（OrderExecutionOperateServiceImplTest 范式），MDC traceId
 * 手工置入模拟 TraceIdFilter 前提。
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationInterceptorTest {

    /** 测试用 traceId：模拟 TraceIdFilter 置入 MDC 的追踪锚点 */
    private static final String TRACE_ID = "authz-trace-anchor";

    /** 护理执行面样本权限点：NURSE/HEAD_NURSE 可用（CASHIER 访问即角色不符样本） */
    private static final PermissionRegistry.PermissionEntry NURSING_ENTRY =
            new PermissionRegistry.PermissionEntry("POST /api/v1/nursing/assignments", Set.of("NURSE", "HEAD_NURSE"));

    @Mock
    private PermissionRegistry registry;

    private AuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthorizationInterceptor(registry, new ObjectMapper());
        MDC.put(SecurityConstants.TRACE_ID_MDC_KEY, TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        // 防御性清理：角色上下文与 MDC 串号会泄漏到其他用例（本拦截器只读不清理，测试侧自清）
        RoleContextHolder.clear();
        MDC.remove(SecurityConstants.TRACE_ID_MDC_KEY);
    }

    @Test
    @DisplayName("ADMIN 一票放行（D3）：已登记端点与未登记路径均放行，判定先于矩阵解析（不触达 resolve）")
    void adminRolePassesThroughAnything() throws Exception {
        RoleContextHolder.set(List.of("ADMIN"));

        // ADMIN 分支不依赖矩阵：无论登记与否一律放行（不 stub resolve 即断言"未触达"语义）
        assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response(), new Object()))
                .isTrue();
        assertThat(interceptor.preHandle(request("GET", "/api/v1/not/registered"), response(), new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("命中权限点且角色交集非空：NURSE 访问护理执行面放行")
    void permittedRolePassesWhenIntersectionNotEmpty() throws Exception {
        RoleContextHolder.set(List.of("NURSE"));
        when(registry.resolve("POST", "/api/v1/nursing/assignments")).thenReturn(Optional.of(NURSING_ENTRY));

        assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response(), new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("命中但角色不符：403 ProblemDetail（SYS-1033 + status + contentType + traceId + detail）")
    void mismatchedRoleGets403ProblemDetail() throws Exception {
        RoleContextHolder.set(List.of("CASHIER"));
        when(registry.resolve("POST", "/api/v1/nursing/assignments")).thenReturn(Optional.of(NURSING_ENTRY));
        MockHttpServletResponse response = response();

        boolean proceed = interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"title\":\"Forbidden\"")
                .contains("\"status\":403")
                .contains("\"errorCode\":\"" + SystemErrorCode.PERMISSION_DENIED.getCode() + "\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"")
                .contains("无权访问该功能（权限不足）");
    }

    @Test
    @DisplayName("未登记路径放行（D4 医疗可用性优先）：登录态角色照旧通行")
    void unregisteredPathPassesWithRoles() throws Exception {
        RoleContextHolder.set(List.of("DOCTOR"));

        assertThat(interceptor.preHandle(request("GET", "/api/v1/system/dicts/gender"), response(), new Object()))
                .isTrue();
    }

    @Test
    @DisplayName("未登记路径首见留 warn 痕：消息携带 uri（完整性缺口由 RbacMatrixIT 对照断言守护）")
    void unregisteredPathPassLogsWarnWithUri() throws Exception {
        RoleContextHolder.set(List.of("DOCTOR"));
        Logger interceptorLogger = (Logger) LoggerFactory.getLogger(AuthorizationInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        interceptorLogger.addAppender(appender);
        try {
            interceptor.preHandle(request("GET", "/api/v1/system/dicts/gender"), response(), new Object());

            assertThat(appender.list)
                    .anyMatch(event -> Level.WARN.equals(event.getLevel())
                            && event.getFormattedMessage().contains("/api/v1/system/dicts/gender"));
        } finally {
            // 测试挂载的 appender 必须卸下，防污染后续用例的日志断言
            interceptorLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("未登记路径重复请求告警降级：同 URI 仅首见 warn，后续降 debug（哨兵轮询不刷屏）")
    void unregisteredPathRepeatedRequestDowngradesToDebug() throws Exception {
        RoleContextHolder.set(List.of("DOCTOR"));
        Logger interceptorLogger = (Logger) LoggerFactory.getLogger(AuthorizationInterceptor.class);
        interceptorLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        interceptorLogger.addAppender(appender);
        try {
            // 同 URI 连续三次请求：首见 warn 一条，后续全部降 debug（评审 A-3/B-2 修复环语义）
            for (int i = 0; i < 3; i++) {
                assertThat(interceptor.preHandle(request("GET", "/api/v1/ward/board/W01"), response(), new Object()))
                        .isTrue();
            }

            assertThat(appender.list.stream().filter(event -> Level.WARN.equals(event.getLevel())))
                    .as("同 URI 仅首见一条 warn")
                    .hasSize(1);
            assertThat(appender.list.stream().filter(event -> Level.DEBUG.equals(event.getLevel())))
                    .as("重复请求降 debug 留痕")
                    .hasSize(2);
        } finally {
            interceptorLogger.detachAppender(appender);
            // 恢复级别继承（null=沿用父级有效级别），防降级泄漏影响后续用例的 warn 断言
            interceptorLogger.setLevel(null);
        }
    }

    @Test
    @DisplayName("哨兵（角色空集）双面：未登记路径放行，已登记路径 403（空集与允许集交集必空）")
    void sentinelEmptyRolesPassUnregisteredButRejectedOnRegistered() throws Exception {
        RoleContextHolder.set(List.of());
        when(registry.resolve("POST", "/api/v1/nursing/assignments")).thenReturn(Optional.of(NURSING_ENTRY));
        when(registry.resolve("GET", "/api/v1/ward/infusion-board/W01")).thenReturn(Optional.empty());

        assertThat(interceptor.preHandle(request("GET", "/api/v1/ward/infusion-board/W01"), response(), new Object()))
                .isTrue();
        MockHttpServletResponse response = response();
        assertThat(interceptor.preHandle(request("POST", "/api/v1/nursing/assignments"), response, new Object()))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"errorCode\":\"" + SystemErrorCode.PERMISSION_DENIED.getCode() + "\"");
    }

    /** 构造裸请求（角色上下文与登记面均不经请求头承载）。 */
    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    /** 构造 mock 响应（每断言独立实例，防线程复用串响应体）。 */
    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }
}
