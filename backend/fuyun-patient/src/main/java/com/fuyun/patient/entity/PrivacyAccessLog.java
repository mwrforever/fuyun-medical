package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 敏感查阅留痕实体（patient.privacy_access_log 只增表）：与 M01 审计互补（M01 记谁动了系统，
 * 本表记看了谁的什么——FU-M02-06 双留痕的台账侧锚点）。
 *
 * <p>V503 只增表口径：无 updated_at/created_by/deleted（无 UPDATE 语义，不挂触发器不设逻辑删）；
 * occurred_at/created_at 由数据库 DEFAULT now() 维护，应用层不写。
 */
@Getter
@Setter
@TableName("patient.privacy_access_log")
public class PrivacyAccessLog {

    /** 主键：雪花 ID（MP ASSIGN_ID 自动生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人（OperatorContextHolder 注入；未认证回退 system） */
    private String operatorId;

    /** 被查阅患者主索引 */
    private Long patientId;

    /** 查阅类型（UNMASK_QUERY 明文查阅；ARCHIVE_EXPORT/PANORAMA_VIEW 词表预留不实现） */
    private String accessType;

    /** 查阅目的（必填，个保法最小必要留痕） */
    private String purpose;

    /** 查阅字段清单（逗号分隔字段词） */
    private String fields;

    /** 查阅时刻（业务时刻；数据库 DEFAULT now() 维护，应用层不写） */
    private OffsetDateTime occurredAt;

    /** 全链路追踪号（MDC 取值，可空） */
    private String traceId;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;
}
