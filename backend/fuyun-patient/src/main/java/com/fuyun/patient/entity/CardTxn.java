package com.fuyun.patient.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 只增流水台账（V503 口径）：无 UPDATE 语义。
 *
 * <p>不挂触发器、不设 deleted（V104）；balance_after 取记账单语句 UPDATE 的 RETURNING 原子回读值，
 * 为对账核对锚点；本表仅 INSERT/SELECT，任何 UPDATE/DELETE 均违反台账红线。
 */
@Getter
@Setter
@TableName("patient.card_txn")
public class CardTxn {

    /** 流水 id（雪花，MP ASSIGN_ID 插入时生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 账户 id（card_account 主键） */
    private Long accountId;

    /** 记账类型：RECHARGE 充值/PAY 消费/REFUND 退款/REVERSE 冲正（CardTxnType，按 name 存取） */
    private String txnType;

    /** 金额（分，恒为正数；方向由 txn_type 表达） */
    private Long amount;

    /** 记账后余额（分；RETURNING 原子回读，对账核对锚点） */
    private Long balanceAfter;

    /** M13 收费单据引用（记账与资金动作分离的对账关联键，可空） */
    private String bizRef;

    /** 记账业务时刻：数据库 DEFAULT now() 维护 */
    private OffsetDateTime occurredAt;

    /** 创建时间：数据库 DEFAULT now() 维护 */
    private OffsetDateTime createdAt;
}
