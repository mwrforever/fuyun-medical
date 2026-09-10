package com.fuyun.system.internal;

import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.utils.SensitiveMasker;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.enums.AuditResult;
import com.fuyun.system.record.AuditLogEntry;
import com.fuyun.system.service.IAuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计切面（B3.3 交付，BRIEF-PR3-01 §3.3）：环绕拦截标注 {@link AuditLog} 的 controller
 * 方法，落 system.audit_log 只增表。
 *
 * <p>拦截契约：proceed 成功记 SUCCESS；任意异常记 FAIL（fail_reason=异常消息经脱敏与 500 字符
 * 截断）后原样 rethrow。落库 try-catch 全吞：失败仅 error 日志告警，绝不阻断业务（M01 模块红线）。
 * 切面落 controller 层注解点——业务事务已在 service 提交，审计写入天然在事务外（解耦），
 * 且业务回滚仍能记 FAIL。
 *
 * <p>字段口径：操作人取 OperatorContextHolder（免认证登录端点无上下文，回退取入参 LoginRequest
 * 登录名作审计主体，两者皆缺回退 system）；traceId 取 MDC（TraceIdFilter 前置于切面，必可用）；
 * resource/client_ip 取 RequestContextHolder 当前请求（非 HTTP 线程兜底 unknown）。
 *
 * <p>脱敏红线：fail_reason/detail 统一经 SensitiveMasker 处理，密码/令牌入参在摘要组装期显式
 * 打码——含 String 形态的 Authorization 头原文（{@code Bearer <令牌>}，logout 端点入参），禁明文
 * 入审计（§8-5，审核 C-1）。全部 VARCHAR 列（operator/resource/trace_id/client_ip）组装期
 * truncate 收口列宽防线，防超长注入致整行写入失败被吞、审计静默丢失（B3.3 审核 Minor-2）。
 * P0 同步写（controller 层、事务外、try-catch 告警），异步批量 P1（简报 §9-5）；切面落
 * internal/（模块内横切设施非对外契约，backend 宪法 B.1），Bean 注册点为 SystemWebConfig
 * @Import（AOP 自动代理由 fuyun-app spring-boot-starter-aop 装配生效）。
 */
@Slf4j
@Aspect
public class AuditLogAspect {

    /** fail_reason 列宽防线：异常消息经脱敏后截断到 500 字符（V302 VARCHAR(500)） */
    private static final int FAIL_REASON_MAX_LENGTH = 500;

    /** detail 列宽防线：参数摘要截断到 1000 字符（V302 VARCHAR(1000)） */
    private static final int DETAIL_MAX_LENGTH = 1000;

    /** operator_id 列宽防线：操作人标识截断到 64 字符（V302 VARCHAR(64)，超长致整行写入失败须收口） */
    private static final int OPERATOR_MAX_LENGTH = 64;

    /** resource 列宽防线：请求 URI 截断到 256 字符（V302 VARCHAR(256)） */
    private static final int RESOURCE_MAX_LENGTH = 256;

    /** trace_id 列宽防线：追踪 ID 截断到 64 字符（V302 VARCHAR(64)，防超长 X-Trace-Id 注入） */
    private static final int TRACE_ID_MAX_LENGTH = 64;

    /** client_ip 列宽防线：客户端 IP 截断到 64 字符（V302 VARCHAR(64)） */
    private static final int CLIENT_IP_MAX_LENGTH = 64;

    /** 请求上下文缺失（非 HTTP 线程）时 resource/client_ip 的兜底取值 */
    private static final String UNKNOWN_CONTEXT_VALUE = "unknown";

    /** 无操作人上下文且无登录名入参时的操作人兜底取值（与 created_by 系统操作口径一致） */
    private static final String SYSTEM_OPERATOR = "system";

    /** 敏感字段打码占位：登录口令/刷新令牌/Authorization 头原文在参数摘要中的固定替代值 */
    private static final String MASKED_SENSITIVE_VALUE = "***";

    private final IAuditLogService auditLogService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1；本类不加 stereotype 注解）。
     *
     * @param auditLogService 审计日志服务，非空；注入接口类型（B.2-2）
     */
    public AuditLogAspect(IAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * 环绕拦截：执行目标方法并按结果落审计留痕（SUCCESS/FAIL + 原样 rethrow）。
     *
     * @param joinPoint 连接点（注解方法执行），非空
     * @param auditLog  方法上的审计注解，非空；actionType 决定落库动作类型
     * @return 目标方法返回值，原样透传
     * @throws Throwable 目标方法异常，脱敏留痕后原样上抛（禁止吞错改变异常语义）
     */
    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            record(auditLog, AuditResult.SUCCESS, null, joinPoint.getArgs());
            return result;
        } catch (Throwable cause) {
            // 任意异常记 FAIL：原因取异常消息（脱敏 + 截断），原样 rethrow 交全局渲染器/容器
            record(auditLog, AuditResult.FAIL, cause.getMessage(), joinPoint.getArgs());
            throw cause;
        }
    }

    /**
     * 组装并写入审计留痕：全部 VARCHAR 列在组装期经 SensitiveMasker.truncate 收口列宽防线
     * （防超长注入——如伪造超长 X-Trace-Id——导致整行写入失败被吞、审计静默丢失），落库任何
     * 异常全吞仅 error 告警（审计不阻断业务红线）。
     *
     * @param auditLog   审计注解，非空
     * @param result     审计结果（SUCCESS/FAIL），非空
     * @param failReason 失败原因原文，可空（成功行为 null）；落库前经脱敏与截断
     * @param args       目标方法入参，可空；摘要组装时敏感字段显式打码
     */
    private void record(AuditLog auditLog, AuditResult result, String failReason, Object[] args) {
        try {
            auditLogService.append(new AuditLogEntry(
                    SensitiveMasker.truncate(resolveOperator(args), OPERATOR_MAX_LENGTH),
                    auditLog.actionType(),
                    SensitiveMasker.truncate(currentResource(), RESOURCE_MAX_LENGTH),
                    null,
                    SensitiveMasker.truncate(currentClientIp(), CLIENT_IP_MAX_LENGTH),
                    SensitiveMasker.truncate(MDC.get(SecurityConstants.TRACE_ID_MDC_KEY), TRACE_ID_MAX_LENGTH),
                    result,
                    sanitizeReason(failReason),
                    buildDetail(args),
                    OffsetDateTime.now()));
        } catch (Exception e) {
            // 审计落库失败仅告警不阻断业务：error 日志即 P0 告警通道（含定位要素，禁含敏感值）
            log.error(
                    "审计日志落库失败（不阻断业务）：actionType={}，result={}，resource={}，traceId={}",
                    auditLog.actionType(),
                    result,
                    currentResource(),
                    MDC.get(SecurityConstants.TRACE_ID_MDC_KEY),
                    e);
        }
    }

    /**
     * 解析操作人：优先操作人上下文（认证拦截器注入）；免认证登录端点无上下文，回退取入参
     * LoginRequest 登录名作审计主体；两者皆缺（系统/非 HTTP 场景）回退 system。
     *
     * @param args 目标方法入参，可空
     * @return 操作人标识，非空
     */
    private String resolveOperator(Object[] args) {
        String operator = OperatorContextHolder.get();
        if (operator != null) {
            return operator;
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof LoginRequest loginRequest) {
                    return loginRequest.loginName();
                }
            }
        }
        return SYSTEM_OPERATOR;
    }

    /**
     * 取当前请求 URI；请求上下文缺失（非 HTTP 线程，如单元装配场景）兜底 unknown。
     *
     * @return 请求 URI 或 unknown，非空
     */
    private String currentResource() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getRequestURI() : UNKNOWN_CONTEXT_VALUE;
    }

    /**
     * 取当前请求客户端 IP；请求上下文缺失兜底 unknown。
     *
     * @return 客户端 IP 或 unknown，非空
     */
    private String currentClientIp() {
        HttpServletRequest request = currentRequest();
        return request != null ? request.getRemoteAddr() : UNKNOWN_CONTEXT_VALUE;
    }

    /**
     * 取当前 HTTP 请求。
     *
     * @return 当前请求；非 HTTP 线程（无 ServletRequestAttributes）返回 null
     */
    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    /**
     * 失败原因脱敏：异常消息可能携带下游报文片段，统一经身份证/手机号掩码后截断到列宽防线。
     *
     * @param failReason 失败原因原文，可空
     * @return 脱敏截断后的原因；null 原样返回
     */
    private String sanitizeReason(String failReason) {
        if (failReason == null) {
            return null;
        }
        // 先证后机（组合使用约定，SensitiveMasker javadoc）：证号含长数字段，先掩证号再掩手机号
        return SensitiveMasker.truncate(
                SensitiveMasker.maskPhone(SensitiveMasker.maskIdCard(failReason)), FAIL_REASON_MAX_LENGTH);
    }

    /**
     * 组装请求参数摘要（审计 detail）：登录口令/刷新令牌/Authorization 头原文显式打码，其余参数值
     * 直出后截断到列宽防线。
     *
     * <p>禁整对象 toString 直出：LoginRequest/RefreshRequest 为敏感载体，record 默认 toString
     * 会带出明文密码/令牌；String 入参若携带 Bearer 方案前缀（如 logout 端点的 Authorization 头
     * {@code Bearer <令牌>}）等同凭证载体，一律打码（脱敏红线 §8-5，审核 C-1）。
     *
     * @param args 目标方法入参，可空；空参返回 null（detail 列可空）
     * @return 参数摘要（脱敏后），可空
     */
    private String buildDetail(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        StringBuilder detail = new StringBuilder();
        for (Object arg : args) {
            if (detail.length() > 0) {
                detail.append(", ");
            }
            if (arg instanceof LoginRequest loginRequest) {
                // 口令明文禁入审计：仅保留登录名供追溯
                detail.append("loginName=")
                        .append(loginRequest.loginName())
                        .append(",password=")
                        .append(MASKED_SENSITIVE_VALUE);
            } else if (arg instanceof RefreshRequest) {
                // 令牌原文禁入审计（令牌即凭证）
                detail.append("refreshToken=").append(MASKED_SENSITIVE_VALUE);
            } else if (arg instanceof String text && text.startsWith(SecurityConstants.BEARER_PREFIX)) {
                // Authorization 头原文（"Bearer <令牌>"）即凭证载体：保留方案名供语义辨识，令牌值打码
                detail.append(SecurityConstants.BEARER_PREFIX).append(MASKED_SENSITIVE_VALUE);
            } else {
                detail.append(arg);
            }
        }
        return SensitiveMasker.truncate(detail.toString(), DETAIL_MAX_LENGTH);
    }
}
