package com.fuyun.inpatient.vo;

/**
 * 执行回签出参（POST /api/v1/inpatient/order-plans/{no}/execute-confirm，W-33 id 55 字段级
 * 契约逐字：planNo/m04OrderNo/orderStatus(迁移后医嘱头状态)/planStatus(迁移后计划状态)）。
 * 幂等路径（重复回签已 EXECUTED 计划）返回当前状态——不迁移不发事件的只读应答形态。
 *
 * @param planNo      计划号，非空
 * @param m04OrderNo  所属医嘱号，非空
 * @param orderStatus 迁移后医嘱头状态（OrderStatus 八态 code；幂等路径=当前实态），非空
 * @param planStatus  迁移后计划状态（PlanStatus 三态 code；幂等路径=EXECUTED），非空
 */
public record ExecuteConfirmVO(String planNo, String m04OrderNo, String orderStatus, String planStatus) {}
