package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.ArrearsApprovalStatus;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 挂账审批实体（billing.arrears_approval，V1002，Spec M-10 四态）：出院欠费挂账单——预审
 * BLOCKED 的出院申请经审批放行后发布 billing.arrears.approved（id 73），approved_balance 为
 * 审批时点押金余额快照（分，载荷 approvedBalance 同源）。状态迁移唯一经
 * ArrearsApprovalMapper.casDecide 条件更新（PENDING_APPROVAL 唯一可决出边，并发被抢 0 行拒）。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.arrears_approval")
public class ArrearsApproval {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 审批单号（AR+服务端序列号，业务唯一，uk WHERE deleted=0） */
    private String approvalNo;

    /** CF-3 住院就诊号（I 型 14 位） */
    private String visitId;

    /** 申请理由（欠费挂账缘由） */
    private String applyReason;

    /** 审批人（操作者上下文注入；决定时回填） */
    private String approver;

    /** 决定时间（通过/驳回同语句落，库端 now()） */
    private OffsetDateTime decidedAt;

    /** 批准时点押金余额快照（分；APPROVED 必填，id 73 载荷 approvedBalance 同源） */
    private Long approvedBalance;

    /** 审批状态机（ArrearsApprovalStatus 四态） */
    private ArrearsApprovalStatus status;

    /** 审计列：库维护 */
    private OffsetDateTime createdAt;

    /** 审计列：库维护 */
    private OffsetDateTime updatedAt;

    /** 审计列：操作人应用层注入 */
    private String createdBy;

    /** 审计列 */
    private String updatedBy;

    /** 逻辑删标记（@TableLogic 全局配置） */
    @TableLogic
    private Short deleted;
}
