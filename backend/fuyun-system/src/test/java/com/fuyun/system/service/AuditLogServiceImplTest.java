package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fuyun.system.entity.AuditLogEntity;
import com.fuyun.system.enums.AuditActionType;
import com.fuyun.system.enums.AuditResult;
import com.fuyun.system.mapper.AuditLogMapper;
import com.fuyun.system.record.AuditLogEntry;
import com.fuyun.system.service.impl.AuditLogServiceImpl;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 审计日志服务单元测试（B3.3 交付，BRIEF-PR3-01 §3.3）。
 *
 * <p>覆盖：append 将参数对象逐字段映射为只增实体并单语句插入（不开方法级事务，单语句自原子）。
 * 端到端落库与查询语义归 AuthFlowIT 步骤 7。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Captor
    private ArgumentCaptor<AuditLogEntity> entityCaptor;

    private AuditLogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditLogServiceImpl(auditLogMapper);
    }

    @Test
    @DisplayName("append：参数对象逐字段映射为审计实体并单语句插入（只增表无更新路径）")
    void appendMapsEntryToAuditEntityAndInsertsOnce() {
        OffsetDateTime occurredAt = OffsetDateTime.now();
        AuditLogEntry entry = new AuditLogEntry(
                "42",
                AuditActionType.LOGIN,
                "/api/v1/system/auth/login",
                null,
                "10.0.0.1",
                "trace-1",
                AuditResult.FAIL,
                "登录名或密码错误",
                "loginName=admin,password=***",
                occurredAt);

        service.append(entry);

        verify(auditLogMapper).insert(entityCaptor.capture());
        AuditLogEntity entity = entityCaptor.getValue();
        assertThat(entity.getOperatorId()).isEqualTo("42");
        assertThat(entity.getActionType()).isEqualTo(AuditActionType.LOGIN);
        assertThat(entity.getResource()).isEqualTo("/api/v1/system/auth/login");
        assertThat(entity.getBizNo()).isNull();
        assertThat(entity.getClientIp()).isEqualTo("10.0.0.1");
        assertThat(entity.getTraceId()).isEqualTo("trace-1");
        assertThat(entity.getResult()).isEqualTo(AuditResult.FAIL);
        assertThat(entity.getFailReason()).isEqualTo("登录名或密码错误");
        assertThat(entity.getDetail()).isEqualTo("loginName=admin,password=***");
        assertThat(entity.getOccurredAt()).isEqualTo(occurredAt);
    }
}
