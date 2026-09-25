package com.fuyun.inpatient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 住院医嘱审核流水实体（inpatient.order_audit，V905）——审核链结论留痕（只增）：SYSTEM
 * 系统自动审核行（开立后全员必经预检）与 PHARMACIST 药师审方行（M06 回执驱动）。用药类
 * 医嘱 CREATED 停留期语义=「待药师审」即以本表 stage 区分（04 Spec 红线 2——不新增医嘱
 * 状态）。业务面仅 INSERT（对齐 billing.deposit_txn 流水先例，审计列与逻辑删列保留形态）。
 */
@Getter
@Setter
@TableName("inpatient.order_audit")
public class OrderAudit {

    /** 雪花主键（MP ASSIGN_ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属医嘱主键（medical_order.id） */
    private Long orderId;

    /** 审核阶段（AuditStage 两值：SYSTEM 系统自动审核/PHARMACIST 药师审方） */
    private String stage;

    /** 审核方引用（M06 review_task 审方任务单号；SYSTEM 行 null） */
    private String reviewTaskNo;

    /** 审核结论（两值词表：PASSED 通过/REJECTED 驳回） */
    private String conclusion;

    /** 审核理由（驳回时=药师意见必填；通过时可空或预检说明） */
    private String reason;

    /** 审核操作者（SYSTEM 行=开立医生员工 ID；PHARMACIST 行=审方药师员工 ID） */
    private String auditOperator;

    /** 审核发生时点（SYSTEM=预检时点；PHARMACIST=M06 回执 auditedAt） */
    private OffsetDateTime occurredAt;

    /** 创建时刻 */
    private OffsetDateTime createdAt;

    /** 更新时刻 */
    private OffsetDateTime updatedAt;

    /** 创建者 */
    private String createdBy;

    /** 更新者 */
    private String updatedBy;

    /** 逻辑删标记 */
    @TableLogic
    private Integer deleted;
}
