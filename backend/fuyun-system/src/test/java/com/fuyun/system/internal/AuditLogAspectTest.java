package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.enums.AuditResult;
import com.fuyun.system.record.AuditLogEntry;
import com.fuyun.system.service.IAuditLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计切面单元测试（B3.3 交付，BRIEF-PR3-01 §3.3）。
 *
 * <p>覆盖：成功路径记 SUCCESS（操作人取上下文、traceId 取 MDC、resource/client_ip 取当前请求）、
 * 免认证登录端点无操作人上下文时回退取入参登录名（审计主体口径）、BizException/任意异常记 FAIL
 * 且原样 rethrow、fail_reason 脱敏 + 500 字符截断、审计落库失败全吞不阻断业务（成功与失败双路径）、
 * 请求上下文缺失兜底 unknown。落库字段语义的真库落点归 AuthFlowIT 步骤 7 端到端断言。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogAspectTest {

    private static final String TRACE_ANCHOR = "aspect-test-trace";

    @Mock
    private IAuditLogService auditLogService;

    @Captor
    private ArgumentCaptor<AuditLogEntry> entryCaptor;

    private AuditLogAspect aspect;

    private SampleService proxy;

    @BeforeEach
    void setUp() {
        aspect = new AuditLogAspect(auditLogService);
        // CGLIB 代理承载 @annotation 切点（注解落实现类方法，须类代理方可见）
        AspectJProxyFactory factory = new AspectJProxyFactory(new SampleService());
        factory.addAspect(aspect);
        proxy = factory.getProxy();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/system/practice/check");
        request.setRemoteAddr("10.20.30.40");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MDC.put("traceId", TRACE_ANCHOR);
    }

    @AfterEach
    void tearDown() {
        // ThreadLocal 逐一清理，防用例间串号（与生产请求收尾同口径）
        RequestContextHolder.resetRequestAttributes();
        OperatorContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("成功路径：记 SUCCESS 行，操作人取上下文、traceId/resource/client_ip 与当前请求一致")
    void successPathRecordsSuccessEntryFromRequestContext() {
        OperatorContextHolder.set("42");

        String result = proxy.write("hello");

        assertThat(result).isEqualTo("ok");
        verify(auditLogService).append(entryCaptor.capture());
        AuditLogEntry entry = entryCaptor.getValue();
        assertThat(entry.operatorId()).isEqualTo("42");
        assertThat(entry.actionType()).isEqualTo(AuditActionType.WRITE);
        assertThat(entry.resource()).isEqualTo("/api/v1/system/practice/check");
        assertThat(entry.clientIp()).isEqualTo("10.20.30.40");
        assertThat(entry.traceId()).isEqualTo(TRACE_ANCHOR);
        assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(entry.failReason()).isNull();
        assertThat(entry.detail()).contains("hello");
        assertThat(entry.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("免认证登录端点：无操作人上下文回退取入参登录名，detail 中口令打码不落明文")
    void loginWithoutOperatorContextFallsBackToLoginNameAndMasksPassword() {
        String result = proxy.login(new LoginRequest("admin", "secret-password"));

        assertThat(result).isEqualTo("token");
        verify(auditLogService).append(entryCaptor.capture());
        AuditLogEntry entry = entryCaptor.getValue();
        assertThat(entry.operatorId()).isEqualTo("admin");
        assertThat(entry.actionType()).isEqualTo(AuditActionType.LOGIN);
        assertThat(entry.detail()).contains("loginName=admin");
        // 脱敏红线：口令明文禁入审计 detail
        assertThat(entry.detail()).doesNotContain("secret-password");
    }

    @Test
    @DisplayName("刷新令牌入参：detail 中令牌打码不落原文")
    void refreshArgIsMaskedInDetail() {
        proxy.refresh(new RefreshRequest("raw-refresh-token"));

        verify(auditLogService).append(entryCaptor.capture());
        assertThat(entryCaptor.getValue().detail()).doesNotContain("raw-refresh-token");
    }

    @Test
    @DisplayName("Authorization 头入参：detail 中 Bearer 令牌打码不落原文（logout 端点防回归，审核 C-1）")
    void bearerHeaderArgIsMaskedInDetail() {
        proxy.logout("Bearer it-only-raw-access-token-value");

        verify(auditLogService).append(entryCaptor.capture());
        AuditLogEntry entry = entryCaptor.getValue();
        assertThat(entry.detail()).doesNotContain("it-only-raw-access-token-value");
    }

    @Test
    @DisplayName("普通字符串入参不受 Bearer 打码误伤：非令牌语义的 String 原样进 detail")
    void plainStringArgIsKeptIntactInDetail() {
        proxy.write("hello");

        verify(auditLogService).append(entryCaptor.capture());
        assertThat(entryCaptor.getValue().detail()).contains("hello");
    }

    @Test
    @DisplayName("业务异常：记 FAIL 行（fail_reason=异常消息）后原样 rethrow；无参无上下文操作人回退 system")
    void bizExceptionRecordsFailEntryAndRethrows() {
        BizException expected =
                new BizException(SystemErrorCode.LOGIN_NAME_OR_PASSWORD_WRONG, HttpStatus.UNAUTHORIZED, "登录名或密码错误");
        proxy.stageFailure(expected);

        assertThatThrownBy(() -> proxy.failNoArgs()).isSameAs(expected);

        verify(auditLogService).append(entryCaptor.capture());
        AuditLogEntry entry = entryCaptor.getValue();
        assertThat(entry.operatorId()).isEqualTo("system");
        assertThat(entry.result()).isEqualTo(AuditResult.FAIL);
        assertThat(entry.failReason()).isEqualTo("登录名或密码错误");
        assertThat(entry.detail()).as("无参方法参数摘要为空").isNull();
    }

    @Test
    @DisplayName("任意异常：记 FAIL 行且 fail_reason 截断到 500 字符后原样 rethrow")
    void unexpectedExceptionReasonIsTruncatedTo500() {
        IllegalStateException expected = new IllegalStateException("长".repeat(600));
        proxy.stageFailure(expected);

        assertThatThrownBy(() -> proxy.failNoArgs()).isSameAs(expected);

        verify(auditLogService).append(entryCaptor.capture());
        assertThat(entryCaptor.getValue().failReason()).hasSize(500);
    }

    @Test
    @DisplayName("落库失败不阻断业务：成功路径吞掉持久化异常，业务返回值不受影响")
    void persistenceFailureIsSwallowedOnSuccessPath() {
        doThrow(new IllegalStateException("库不可用")).when(auditLogService).append(any());

        String result = proxy.write("hello");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("落库失败不阻断业务：失败路径仍原样抛出业务异常（不被持久化异常覆盖）")
    void persistenceFailureDoesNotMaskOriginalFailure() {
        doThrow(new IllegalStateException("库不可用")).when(auditLogService).append(any());
        IllegalStateException expected = new IllegalStateException("boom");
        proxy.stageFailure(expected);

        assertThatThrownBy(() -> proxy.failNoArgs()).isSameAs(expected);
    }

    @Test
    @DisplayName("请求上下文缺失（非 HTTP 线程）：resource/client_ip 兜底 unknown，不阻断审计写入")
    void missingRequestContextFallsBackToUnknownResource() {
        RequestContextHolder.resetRequestAttributes();

        proxy.write("hello");

        verify(auditLogService).append(entryCaptor.capture());
        assertThat(entryCaptor.getValue().resource()).isEqualTo("unknown");
        assertThat(entryCaptor.getValue().clientIp()).isEqualTo("unknown");
    }

    /** 切点目标样本：注解形态与生产 controller 落点一致（方法级 @AuditLog） */
    static class SampleService {

        /** 预置异常：failNoArgs 抛出（供断言原样 rethrow 的同实例对照） */
        private RuntimeException pendingFailure;

        /** 预置下次失败方法抛出的异常（本方法非切点，不被拦截） */
        public void stageFailure(RuntimeException cause) {
            this.pendingFailure = cause;
        }

        @AuditLog(actionType = AuditActionType.WRITE)
        public String write(String arg) {
            return "ok";
        }

        @AuditLog(actionType = AuditActionType.LOGIN)
        public String login(LoginRequest request) {
            return "token";
        }

        @AuditLog(actionType = AuditActionType.LOGIN)
        public String refresh(RefreshRequest request) {
            return "token";
        }

        /** 注解落点形态与 AuthController.logout 一致：唯一入参为 Authorization 头原文 */
        @AuditLog(actionType = AuditActionType.LOGIN)
        public String logout(String authorization) {
            return "ok";
        }

        @AuditLog(actionType = AuditActionType.WRITE)
        public String failNoArgs() {
            throw pendingFailure;
        }
    }
}
