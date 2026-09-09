package com.fuyun.system.service.impl;

import com.fuyun.system.entity.AuditLogEntity;
import com.fuyun.system.mapper.AuditLogMapper;
import com.fuyun.system.record.AuditLogEntry;
import com.fuyun.system.service.IAuditLogService;
import lombok.extern.slf4j.Slf4j;

/**
 * 审计日志服务实现（system.audit_log 只增表唯一写入口，BRIEF-PR3-01 §3.3）。
 *
 * <p>写入形态：append 直接 mapper.insert 单语句（自原子，不开方法级事务——A.4.2-7 最小边界；
 * 切面已在 controller 层事务外调用，审计写入与业务事务天然解耦）。只增红线：本类不提供任何
 * UPDATE/DELETE 路径。装配归 SystemWebConfig @Import（com.fuyun.system 不在组件扫描范围）。
 */
@Slf4j
public class AuditLogServiceImpl implements IAuditLogService {

    /** 审计只增表数据访问：唯一写通道 */
    private final AuditLogMapper auditLogMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param auditLogMapper 审计日志 mapper，非空；来源：同模块 mapper 包
     */
    public AuditLogServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 追加一条审计留痕：参数对象逐字段映射为实体后单语句插入（禁止 UPDATE/DELETE 路径）。
     *
     * @param entry 审计留痕参数对象，非空；敏感字段已由切面脱敏
     * @throws RuntimeException 落库失败原样上抛（唯一索引/连接故障等）；由审计切面统一吞错告警，
     *                          绝不阻断业务
     */
    @Override
    public void append(AuditLogEntry entry) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setOperatorId(entry.operatorId());
        entity.setActionType(entry.actionType());
        entity.setResource(entry.resource());
        entity.setBizNo(entry.bizNo());
        entity.setClientIp(entry.clientIp());
        entity.setTraceId(entry.traceId());
        entity.setResult(entry.result());
        entity.setFailReason(entry.failReason());
        entity.setDetail(entry.detail());
        entity.setOccurredAt(entry.occurredAt());
        auditLogMapper.insert(entity);
    }
}
