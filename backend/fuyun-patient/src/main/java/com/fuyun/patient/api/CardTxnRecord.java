package com.fuyun.patient.api;

/**
 * 一卡通记账参数（A.7 参数对象化）：M13 资金动作完成后的记账登记入参（记账与资金动作分离）。
 *
 * @param accountId 一卡通账户 id；来源：M13 结算上下文
 * @param txnType   记账类型（RECHARGE/PAY/REFUND/REVERSE），非空
 * @param amount    金额（分，恒为正数，>0）；来源：M13 收退付单据
 * @param bizRef    M13 收费单据引用（对账关联键，可空）；来源：M13
 */
public record CardTxnRecord(long accountId, CardTxnType txnType, long amount, String bizRef) {}
