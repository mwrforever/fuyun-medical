package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.RefundStatus;
import com.fuyun.billing.enums.RefundType;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 退费申请实体（billing.refund_request，FU-M13-03）：退费以负向 fee_record+link 表达，
 * 本表承载申请/审批状态机与双人守卫（审批人≠申请人且二级批人≠一级批人，BILL-1020）。线程安全：可变实体仅
 * service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.refund_request")
public class RefundRequest {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 退费编号（R+雪花 id） */
    private String refundNo;

    /** 原结算单 id */
    private Long settlementId;

    /** 患者主索引 */
    private Long patientId;

    /** CF-3 定长就诊号 */
    private String visitId;

    /** 退费分级（DAY_CORRECTION 当日更正/CROSS_DAY 跨日/SETTLED_REFUND 已结算退费） */
    private RefundType refundType;

    /** 申请金额（分，服务端按明细聚合，>0） */
    private Long amount;

    /** 退费理由（必填留痕） */
    private String reason;

    /** 申请人（登录身份注入） */
    private String applicant;

    /** 审批人（终批审批人；双人守卫：≠applicant 且二级批时≠firstApprover，可空） */
    private String approver;

    /** 审批时刻（终批时刻，可空） */
    private OffsetDateTime approvedAt;

    /** 一级审批人（L2 二级审批链一级留痕；连批守卫比对位：二级批人≠本值，可空） */
    private String firstApprover;

    /** 一级审批时刻（L2 二级审批链一级留痕，可空） */
    private OffsetDateTime firstApprovedAt;

    /** 原路退回流水（渠道占位；CARD_BALANCE=台账流水 id，可空） */
    private String paymentRefundRef;

    /** 医保撤销回执引用（insurance_call_log.id，SETTLED_REFUND+医保结算必填，可空） */
    private Long insReverseRef;

    /** 驳回理由（REJECTED 时填写，可空） */
    private String rejectReason;

    /** 免审直退标识（审计抽查检索键） */
    private Boolean autoApproved;

    /** 退费状态机（RefundStatus 五值） */
    private RefundStatus status;

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
