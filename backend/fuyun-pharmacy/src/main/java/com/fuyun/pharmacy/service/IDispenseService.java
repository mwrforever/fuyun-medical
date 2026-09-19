package com.fuyun.pharmacy.service;

/**
 * 发药服务（FU-M06-04 门诊发药闭环）：Task 5 交付缴费放行与费用回执两消费入口；
 * 本任务补齐调剂三段（pick/verify/issue）与工作台回显；退药受理随 Task 7 扩展
 * （接口方法只增不改形）。
 */
public interface IDispenseService {

    /**
     * 缴费放行（charged 消费业务）：就诊维度放行 PENDING_FEE 的门诊/急诊处方——
     * 逐单 CAS 转 PENDING_DISPENSE 并创建 CREATED 发药单与明细入队（Spec §5 流程 1）。
     *
     * @param visitId CF-3 就诊号（charged 占位契约最小已知字段，PR-5 回切按单据精确放行），非空
     */
    void releaseByVisit(String visitId);

    /**
     * 费用回执（fee.created 消费业务）：处方通道（trigger_point=PRESCRIPTION_EFFECTIVE，
     * sourceRef=rx_no 经 billingKey 第三段守卫）驱动 APPROVED→PENDING_FEE（Spec R2-14）。
     *
     * @param billingKey 计费唯一键（patientId|sourceRef|triggerPoint|chargeItemId|billingDate），
     *                   非空；来源：billing.fee.created 载荷
     */
    void markPendingFee(String billingKey);

    /**
     * 配药（CREATED→PICKING）：FEFO 选批并条件锁定（防并发超发），追溯码逐码采集落明细行。
     *
     * @param dispenseNo 调剂单号
     * @param lines      逐行采集面（prescriptionItemId + 追溯码集，逐盒采集「无码不结」口径）
     * @throws BizException PH-1008（缺单）/ PH-1009（状态机违例）/ PH-1010（批次不足）
     */
    void pick(String dispenseNo, java.util.List<com.fuyun.pharmacy.dto.PickLine> lines);

    /**
     * 扫码核对（PICKING→PICKED）：核对药师=当前登录者，双签守卫 verifier≠picker（PH-1011）。
     *
     * @param dispenseNo 调剂单号
     * @throws BizException PH-1008 / PH-1009 / PH-1011
     */
    void verify(String dispenseNo);

    /**
     * 发药签名（PICKED→ISSUED）：批次扣减+出库流水同事务，处方转 DISPENSED，
     * 发布 pharmacy.dispense.completed（批次摘要）；发药前重申双签守卫。
     *
     * @param dispenseNo 调剂单号
     * @throws BizException PH-1008 / PH-1009 / PH-1011 / PH-1010（锁定不足违例）
     */
    void issue(String dispenseNo);

    /**
     * 按处方号查发药单（前端工作台回显）。
     *
     * @param rxNo 处方号，非空
     * @return 发药单出参；无单返回 null
     */
    com.fuyun.pharmacy.vo.DispenseVO getByRxNo(String rxNo);
}
