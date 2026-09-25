package com.fuyun.inpatient.service;

import com.fuyun.inpatient.entity.MedicalOrder;
import com.fuyun.inpatient.enums.OrderStatus;

/**
 * 医嘱状态机服务（04-inpatient Spec §3.3 红线 2 承载）：医嘱状态迁移唯一执行与裁决面——
 * 八态合法迁移表（OrderStatus.canTransitionTo）硬编码校验，模块外直写医嘱状态为红线违例。
 * 本服务只管状态面（校验 + CAS + 迁移留痕），<b>不发事件</b>——事件发布归各业务服务在
 * 同事务附带（避免状态机与业务面双写事件）；停嘱/作废等值面补写（end_at/stop_reason）
 * 亦归业务服务。order_status_log 只增留痕数据面归 Task 6 V905 建表后回接（当前版本以
 * 结构化日志承载迁移留痕，回接点见 impl appendStatusLog）。
 */
public interface OrderStateMachineService {

    /**
     * 医嘱状态迁移（合法迁移表校验 + CAS + 留痕）。
     *
     * @param order    迁移目标医嘱行（status 为迁移前实态，迁移成功后内存对象同步置目标态），非空
     * @param to       目标态，非空；来源：业务动作语义（停嘱=STOPPED、审核通过=AUDITED 等）
     * @param reason   迁移原因（留痕面：转科/医生停嘱理由/审核结论等），非空
     * @param operator 操作者员工 ID（审计留痕，Long 形态对齐 PracticeCheckPort 口径），非空
     * @throws com.fuyun.common.exception.BizException IP-1010（409）迁移路径在合法迁移表外
     *                 （含终态再迁移）或 CAS 零行（并发迁移窗口——他方先迁，from 态失配）时触发；
     *                 建议处理策略：拒绝并提示刷新医嘱当前状态，禁止重试盲迁
     */
    void transition(MedicalOrder order, OrderStatus to, String reason, Long operator);
}
