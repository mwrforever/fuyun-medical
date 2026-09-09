package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.AuditActionType;
import com.fuyun.system.enums.AuditResult;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 审计日志实体（system.audit_log，V302 迁移）：操作留痕只增表载体。
 *
 * <p>只增红线（backend 宪法 A.4.2-9 与简报 §2.3）：无 updated_at 列、不挂触发器、无 deleted 列
 * 且本实体不标 @TableLogic——应用层对审计行零 UPDATE/DELETE，留存 ≥6 个月为等保红线
 * （M01 目标 ≥3 年），归档清理策略随 P1+。写入唯一入口为 AuditLogServiceImpl.append。
 */
@Getter
@Setter
@TableName("system.audit_log")
public class AuditLogEntity {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成），禁止手动赋值 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人标识：OperatorContextHolder 值（免认证端点回退取登录名；系统操作为 system） */
    private String operatorId;

    /** 动作类型：LOGIN/WRITE/PRINT/SENSITIVE_QUERY（@EnumValue 映射 VARCHAR 列） */
    private AuditActionType actionType;

    /** 资源 = 请求 URI（如 /api/v1/system/auth/login） */
    private String resource;

    /** 业务单据号，可空；关联业务排障锚点（P0 通用切面无业务单据语义，恒 null） */
    private String bizNo;

    /** 客户端 IP（当前请求 remoteAddr） */
    private String clientIp;

    /** 全链路追踪 ID：与日志/MDC 同源（TraceIdFilter 注入） */
    private String traceId;

    /** 审计结果：SUCCESS/FAIL（@EnumValue 映射 VARCHAR 列） */
    private AuditResult result;

    /** 失败原因（异常消息经脱敏与 500 字符截断），可空；成功行为 null */
    private String failReason;

    /** 审计明细摘要（请求参数摘要，敏感字段打码），可空；禁密码/令牌/执业证书号明文 */
    private String detail;

    /** 业务发生时刻：切面记录点取 now */
    private OffsetDateTime occurredAt;

    /** 落库时刻：数据库 DEFAULT now() 维护（只增表无 updated_at） */
    private OffsetDateTime createdAt;
}
