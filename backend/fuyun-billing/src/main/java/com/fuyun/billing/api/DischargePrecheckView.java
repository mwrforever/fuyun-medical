package com.fuyun.billing.api;

/**
 * 出院费用预审快照视图（BillingAccountQueryPort.precheck 返回投影，M04/M13 进程内对接面）：
 * M13 权威费用数据的只读回显——M04 据此定预审态（settled=true → READY / false → BLOCKED
 * 附欠费额快照），禁增删改（Task 9/13 消费冻结契约）。金额以分承载（A.4.2-8 分值制，
 * 跨模块不换算）。
 *
 * @param unsettledAmount 未结清费用合计（分；未确认/未结算住院费用行聚合），非空
 * @param depositBalance  押金余额（分；无押金账户为 0），非空
 * @param settled         是否结清（结清口径=未结清合计 ≤ 押金余额——押金覆盖内预审通过），非空
 */
public record DischargePrecheckView(Long unsettledAmount, Long depositBalance, boolean settled) {}
