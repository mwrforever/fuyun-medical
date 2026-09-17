package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 退费费用关联实体（billing.refund_fee_link，FU-M13-03）：退费申请与原费用明细多对多桥，
 * 支持部分退（refund_quantity 可小数）；金额勾稽第一层校验数据源（Σlink=申请额）。
 * 线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.refund_fee_link")
public class RefundFeeLink {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 退费申请 id */
    private Long refundId;

    /** 原费用明细 id */
    private Long feeId;

    /** 本次退费数量（DECIMAL(12,3)，>0，支持部分退） */
    private BigDecimal refundQuantity;

    /** 本次退费金额（分，>0） */
    private Long refundAmount;

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
