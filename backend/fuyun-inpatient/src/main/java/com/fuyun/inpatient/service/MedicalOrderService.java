package com.fuyun.inpatient.service;

/**
 * 住院医嘱域服务（FU-M04-04/05）——P2 PR-1 医嘱闭环门面。接口先行冻结（Task 4 转科编排
 * 消费面），实现归 Task 5（MedicalOrderServiceImpl）随 V904 医嘱三表落地；禁空实现桩
 * （本文件为接口声明非桩）。
 */
public interface MedicalOrderService {

    /**
     * 转科自动停嘱（转科编排阶段①，调用方 TransferService.transfer 编排事务内）：转出病区
     * 该就诊全部长期医嘱（order_class=long、非终态）置 STOPPED（stop_reason=转科，停嘱时间=
     * 服务器时间），联动发布 inpatient.order.stopped（M13 按转科时间线截断转出侧持续性费用、
     * M05 撤销未执行执行单）；待执行长期计划随停嘱作废归计划服务（Task 7/8 转科钩子）。
     * 实现须满足编排事务语义：停嘱失败抛异常整体回滚转科事务。
     *
     * @param visitId 住院就诊主键（inpatient_visit.id，非 I 型号），非空；来源：编排起始就诊行
     * @param reason  停嘱原因（转科路径固定「转科」；出院清理复用本接口时另传），非空；来源：编排方
     * @throws com.fuyun.common.exception.BizException 停嘱链状态机违例（IP-1010，实现侧 Task 5 定稿）
     */
    void stopAllForTransfer(Long visitId, String reason);
}
