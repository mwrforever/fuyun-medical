package com.fuyun.patient.api;

/**
 * 一卡通记账登记契约位（2026-09-16 拍板 5：M13 到位前无调用方，实现与单测随 PR-2 齐备）。
 *
 * <p>记账语义：台账串行化——单条 UPDATE 原子改余额 + 插流水（balance_after 为对账锚点）；
 * 出账后余额为负抛 PAT-1016 拒绝（不超扣）；账户非 ACTIVE 抛 PAT-1014。对账逻辑随 PR-3（M13）。
 */
public interface CardAccountLedger {

    /**
     * 登记一笔一卡通流水并原子更新余额（须由调用方在事务内调用，与业务同事务一致）。
     *
     * @param record 记账参数，非空；来源：M13 资金动作（当前 PR-2 无调用方，接口位）
     * @return 流水行 id（card_txn.id）；来源：本模块生成
     * @throws com.fuyun.common.exception.BizException PAT-1013（404 账户不存在）/ PAT-1014（409 状态不允许）/
     *                                                  PAT-1016（409 余额不足）时触发；建议处理策略：
     *                                                  调用方（M13）按业务失败回滚资金动作
     */
    long record(CardTxnRecord record);
}
