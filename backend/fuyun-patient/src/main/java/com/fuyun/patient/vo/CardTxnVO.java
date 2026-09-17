package com.fuyun.patient.vo;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 一卡通流水中出参（GET /card-accounts/{id}/txns 分页元素；balance_after 为对账锚点直出）。
 */
@Getter
@Setter
public class CardTxnVO {

    /** 流水 id */
    private Long id;

    /** 记账类型：RECHARGE/PAY/REFUND/REVERSE */
    private String txnType;

    /** 金额（分，恒为正数；方向由 txn_type 表达） */
    private Long amount;

    /** 记账后余额（分；对账核对锚点） */
    private Long balanceAfter;

    /** M13 收费单据引用（对账关联键，可空） */
    private String bizRef;

    /** 记账业务时刻 */
    private OffsetDateTime occurredAt;
}
