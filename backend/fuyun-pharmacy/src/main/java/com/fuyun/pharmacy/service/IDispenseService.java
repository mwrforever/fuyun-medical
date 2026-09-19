package com.fuyun.pharmacy.service;

/**
 * 发药服务（FU-M06-04 门诊发药闭环）：本任务交付缴费放行与费用回执两消费入口；
 * pick/verify/issue 调剂三段与退药受理随 Task 6/7 扩展（接口方法只增不改形）。
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
}
