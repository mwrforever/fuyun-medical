package com.fuyun.system.service;

import com.fuyun.system.record.AuditLogEntry;

/**
 * 审计日志服务（system.audit_log 只增表唯一写入口，BRIEF-PR3-01 §3.3）。
 *
 * <p>聚合/追加型接口不继承 IService（宪法 A.4.3-20：只增单语句插入无 CRUD 语义面）；
 * 唯一调用方为审计切面（internal/AuditLogAspect），落库失败由切面统一吞错告警（本接口不承担）。
 */
public interface IAuditLogService {

    /**
     * 追加一条审计留痕：参数对象逐字段映射为只增实体后单语句插入。
     *
     * <p>不开方法级事务（单语句自原子，A.4.2-7 最小边界）；本方法抛出的异常由调用方（审计切面）
     * 全吞并 error 告警——审计写入绝不阻断业务（M01 模块红线）。
     *
     * @param entry 审计留痕参数对象，非空；敏感字段须已由调用方脱敏（密码/令牌/执业证书号禁明文）
     */
    void append(AuditLogEntry entry);
}
