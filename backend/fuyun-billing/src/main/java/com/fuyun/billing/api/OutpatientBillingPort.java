package com.fuyun.billing.api;

import java.util.List;

/**
 * 门诊域收费对接端口（M03 门诊域进程内调用，api 包唯一出口；PrescriptionFeePort 消费方命名先例）：
 * 退号退费统一免审档通道（裁决 7——M03 零资金逻辑，资金动作一律本端口进 M13 权威面）与单据费用
 * 组定位/作废面。三个方法的语义与既有 REST 契约逐字同源（POST /refunds、GET /fees?visitId=、
 * POST /fees/{id}/cancel），禁在本端口外新写第二套作废/退费逻辑。
 */
public interface OutpatientBillingPort {

    /**
     * 按 CF-3 就诊号查询费用行组（全状态，id 升序）：M03 退号退费按预约单 fee_settlement_id 勾选
     * 可退行、单据作废按 sourceRef+triggerPoint 定位 PENDING 行的唯一定位面。
     *
     * @param visitId CF-3 门诊就诊号（O 型 14 位），非空；来源：M03 visit/appointment 关联读
     * @return 费用行视图列表（id 升序）；无费用返回空列表
     */
    List<VisitFeeView> feesByVisit(String visitId);

    /**
     * 退费申请（统一免审档通道，裁决 7）：转调 {@code IRefundService.apply}——当日更正免审直退
     * （APPROVED 即发 billing.refund.approved，M03 回执驱动终态）；跨日/超阈进分级审批。
     *
     * @param cmd 退费命令（settlementId/lines/reason），非空；Line.quantity 须为数字串（DECIMAL
     *            string 契约，非数字串由 NumberFormatException 上抛——进程内契约，调用方生成侧保证）
     * @return 新退费申请 id
     * @throws com.fuyun.common.exception.BizException M13 退费守卫（BILL-1014/1017/1021/1030/1031
     *                                                  等，语义见 IRefundService.apply）原样透传
     */
    long applyRefund(VisitRefundCommand cmd);

    /**
     * 单费用行作废（未结算更正通道）：转调 {@code IPricingEngineService.cancel}——仅 PENDING/
     * CONFIRMED 可作废、置 CANCELLED 并释放 billing_key（部分唯一索引语义）；禁新写状态迁移。
     *
     * @param feeId  费用行 id；来源：M03 按 feesByVisit 定位的 PENDING 行
     * @param reason 作废理由（日志/审计留痕锚点），非空白
     * @throws com.fuyun.common.exception.BizException BILL-1010（404 缺行）/ BILL-1011（409 非未结算
     *                                                  态或状态竞态）原样透传（转调不吞）
     */
    void cancelPendingFee(long feeId, String reason);
}
