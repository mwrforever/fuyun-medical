package com.fuyun.billing.api;

/**
 * 门诊域费用行视图（OutpatientBillingPort.feesByVisit 返回投影，M03/M13 进程内对接面）：
 * fee_record 按 visit_id 全状态查询行的跨模块只读投影——六组件即 M03 退号退费与单据作废定位面，
 * 禁增删改（Task 10/12 消费冻结契约）。金额以分承载（A.4.2-8 分值制，跨模块不换算）。
 *
 * @param feeId        费用行 id（refund_fee_link.fee_id 与 cancelPendingFee 定位锚）
 * @param status       费用状态 code（FeeStatus：SETTLED/PART_REFUND 可退、PENDING 可作废等词表）
 * @param sourceRef    来源单据引用（医嘱/申请单号/手工=操作者工号，红线 3 可追溯）
 * @param triggerPoint 计费点 code（TriggerType，M03 按 sourceRef+triggerPoint 精确定位单据费用组）
 * @param amountFen    费用行金额（分，=单价快照×数量 服务端取整，零金额运算出本模块）
 * @param settlementId 结算单 id（未结算为 null；退号退费按预约单 fee_settlement_id 勾选本锚）
 */
public record VisitFeeView(
        Long feeId, String status, String sourceRef, String triggerPoint, Long amountFen, Long settlementId) {}
