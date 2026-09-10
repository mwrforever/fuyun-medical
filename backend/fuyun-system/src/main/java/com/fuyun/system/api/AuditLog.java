package com.fuyun.system.api;

import com.fuyun.system.enums.AuditActionType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（M01 底座能力，BRIEF-PR3-01 §3.3）：标注在需要留痕的 controller 方法上，
 * 由 {@code com.fuyun.system.internal.AuditLogAspect} 环绕拦截落 system.audit_log 只增表。
 *
 * <p>落 api 包为对外契约（backend 宪法 B.1）：其他业务模块将注解自己的 controller 复用 M01
 * 审计底座（审计切面统一由各模块装配引入，跨模块行为语义一致）。
 *
 * <p>切面行为契约：方法成功记 SUCCESS；任意异常记 FAIL（fail_reason=异常消息经脱敏与 500 字符
 * 截断）后原样 rethrow；审计落库失败仅 error 告警绝不阻断业务（M01 模块红线）。操作人取
 * OperatorContextHolder（免认证端点回退取入参登录名）、traceId 取 MDC、resource/client_ip 取
 * 当前请求。P0 注解落点：login/logout（LOGIN）、字典写端点（WRITE）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {

    /**
     * 审计动作类型。
     *
     * @return 动作类型（LOGIN/WRITE/PRINT/SENSITIVE_QUERY），非空；落 audit_log.action_type 列
     */
    AuditActionType actionType();
}
