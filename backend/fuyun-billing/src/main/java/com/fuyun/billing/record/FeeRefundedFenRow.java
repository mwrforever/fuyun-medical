package com.fuyun.billing.record;

/**
 * 单费用行退费聚合投影（XML 聚合 SQL 专用返回载体，{@code RefundRequestMapper.sumDecidedRefundedFenByFeeIds}
 * / {@code sumInFlightRefundedFenByFeeIds} 唯一消费点）：refund_fee_link JOIN refund_request 按
 * status 过滤后的 SUM(refund_amount) 行投影，GROUP BY fee_id + ORDER BY fee_id（宪法 A.4.3-15/17）。
 * 金额语义等价性（PERF-01 SQL 下推）：与旧实现「先按 status 捞退费单 id 集、再集内对 link 逐费用求和」
 * 的两步内存计算结果完全一致——分（fen）口径不变，落库与守卫均直接消费本聚合值。
 *
 * @param feeId      费用行 id（分组键，billing.refund_fee_link.fee_id）
 * @param refundedFen 该费用行在聚合口径下的累计退费金额（分，SUM(refund_amount) 聚合值，恒 &gt;0）
 */
public record FeeRefundedFenRow(Long feeId, Long refundedFen) {}
