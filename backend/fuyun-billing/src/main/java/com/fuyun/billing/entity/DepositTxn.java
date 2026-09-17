package com.fuyun.billing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fuyun.billing.enums.DepositTxnStatus;
import com.fuyun.billing.enums.DepositTxnType;
import com.fuyun.billing.enums.PaymentMethod;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 押金流水实体（billing.deposit_txn，FU-M13-04）：流水只增、退回生成对冲行（ACTIVE 行禁改）；
 * 金额恒正、方向由 txn_type 表达。线程安全：可变实体仅 service 事务内使用，不出数据层。
 */
@Getter
@Setter
@TableName("billing.deposit_txn")
public class DepositTxn {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 押金账户 id */
    private Long accountId;

    /** 流水类型（DEPOSIT 缴入/REFUND 退回/OFFSET 结算抵扣） */
    private DepositTxnType txnType;

    /** 金额（分，恒正，方向由 txn_type 表达） */
    private Long amount;

    /** 支付方式（CASH/BANK/SCAN/ONLINE 四值域） */
    private PaymentMethod paymentMethod;

    /** 渠道流水号（可空） */
    private String channelRef;

    /** 操作者（登录身份） */
    private String operator;

    /** 流水状态 ACTIVE/REFUNDED/OFFSET（ACTIVE 禁改，退回对冲） */
    private DepositTxnStatus status;

    /** 抵扣归属结算单号（OFFSET 行填写，可空） */
    private String offsetSettleNo;

    /** 发生时刻 */
    private OffsetDateTime occurredAt;

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
