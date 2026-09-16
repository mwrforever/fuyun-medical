package com.fuyun.system.record;

import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.enums.AuditResult;
import java.time.OffsetDateTime;

/**
 * 审计留痕参数对象（AuditLogAspect → IAuditLogService.append 的契约载体）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2；A.7-1 参数对象化——字段超出 3 个必须对象化
 * 传参），与 system.audit_log 列一一对应（biz_no 除外，P0 通用切面无业务单据语义）。
 *
 * @param operatorId 操作人标识，非空；来源：OperatorContextHolder（免认证登录端点回退取入参登录名，
 *                   无上下文且无登录名入参回退 system）
 * @param actionType 动作类型，非空；来源：@AuditLog 注解声明
 * @param resource   资源 = 请求 URI，非空；来源：RequestContextHolder 当前请求（非 HTTP 线程兜底 unknown）
 * @param bizNo      业务单据号，可空；P0 恒 null
 * @param clientIp   客户端 IP，非空；来源：当前请求 remoteAddr（非 HTTP 线程兜底 unknown）
 * @param traceId    全链路追踪 ID，可空；来源：MDC（HTTP 请求线程内必非空）
 * @param result     审计结果，非空；SUCCESS/FAIL
 * @param failReason 失败原因（异常消息经脱敏与 500 字符截断），可空；成功行为 null
 * @param detail     请求参数摘要（敏感字段打码），可空；无参方法为 null
 * @param occurredAt 业务发生时刻，非空；来源：切面记录点系统时钟
 */
public record AuditLogEntry(
        String operatorId,
        AuditActionType actionType,
        String resource,
        String bizNo,
        String clientIp,
        String traceId,
        AuditResult result,
        String failReason,
        String detail,
        OffsetDateTime occurredAt) {}
