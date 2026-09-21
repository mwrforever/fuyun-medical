package com.fuyun.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.system.enums.PracticeGrantStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 执业授权实体（system.practice_grant，M01 FU-M01-04）：医师按授权类型（处方权/麻精/抗菌药分级）
 * 的执业资格登记行，practice/check 真实校验与管理端点的数据载体。同一员工同一授权类型仅一条
 * 生效行（部分唯一索引 uk_practice_grant_active 库层兜底）。线程安全：可变实体仅 service 事务内
 * 使用，不出数据层。
 */
@Getter
@Setter
@TableName("system.practice_grant")
public class PracticeGrant {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 员工 ID（sys_employee.id；演示链路与 sys_user.id 同值，应用层保证完整性） */
    private Long employeeId;

    /** 授权类型词表：PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL */
    private String grantType;

    /** 法定依据（执业证书号/批文引用等），可空 */
    private String legalBasis;

    /** 生效日（含当日） */
    private LocalDate validFrom;

    /** 失效日（含当日）；NULL=长期有效 */
    private LocalDate validTo;

    /** 状态机（PracticeGrantStatus：EFFECTIVE/SUSPENDED 落库，EXPIRED 仅读侧派生） */
    private PracticeGrantStatus status;

    /** 审批引用（医务审批单号），可空 */
    private String approvalRef;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护（触发器刷新） */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入（登记端点） */
    private String createdBy;

    /** 审计列：操作人应用层注入（停权 CAS updated_by） */
    private String updatedBy;

    /** 逻辑删标记：0 未删 / 1 已删（@TableLogic，查询自动携带 deleted=0） */
    @TableLogic
    private Short deleted;
}
