-- V302：审计日志表（M01 Spec §4，BRIEF-PR3-01 §2.2/§2.4；只增表——无 updated_at 列、不挂触发器、无 deleted 列，
--   应用层零 UPDATE/DELETE，backend 宪法红线"审计日志只增"；PR-3 B3.3 交付审计切面业务代码，本批先落表）。
-- 留存口径：≥6 个月为等保三级红线，M01 目标 ≥3 年；归档清理策略随 P1+（P0 只增不清理）。

CREATE TABLE system.audit_log (
    id           BIGINT        PRIMARY KEY,                             -- 雪花 ID（MP ASSIGN_ID）
    operator_id  VARCHAR(64)   NOT NULL,                                -- 操作人标识（= OperatorContextHolder 值；系统操作为 system）
    action_type  VARCHAR(16)   NOT NULL,                                -- 动作类型：LOGIN/WRITE/PRINT/SENSITIVE_QUERY
    resource     VARCHAR(256)  NOT NULL,                                -- 资源 = 请求 URI
    biz_no       VARCHAR(128)  NULL,                                    -- 业务单据号（可空；关联业务排障锚点）
    client_ip    VARCHAR(64)   NOT NULL,                                -- 客户端 IP
    trace_id     VARCHAR(64)   NOT NULL,                                -- 全链路追踪 ID（与日志/MDC 同源）
    result       VARCHAR(16)   NOT NULL,                                -- 审计结果：SUCCESS/FAIL
    fail_reason  VARCHAR(500)  NULL,                                    -- 失败原因（脱敏截断后）
    detail       VARCHAR(1000) NULL,                                   -- 审计明细摘要（脱敏后；禁密码/令牌/执业证书号）
    occurred_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),                  -- 业务发生时刻
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()                   -- 落库时刻（只增表无 updated_at）
);

-- 按操作人追溯其操作时间线
CREATE INDEX idx_audit_log_operator_occurred ON system.audit_log (operator_id, occurred_at);
-- 按资源端点回溯访问轨迹
CREATE INDEX idx_audit_log_resource_occurred ON system.audit_log (resource, occurred_at);
-- 按业务单据号串起同一业务的全部审计事件
CREATE INDEX idx_audit_log_biz_no ON system.audit_log (biz_no);
