package com.fuyun.nursing.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.nursing.entity.OrderExecution;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 医嘱执行单生成域服务（V1106 order_execution 业务面，Task 4 三方法）：消费 inpatient 医嘱
 * 事件族驱动执行单三路生成中的两路（转抄临时单/计划批量单）与终态撤销。归属回填自病区患者
 * 投影（nursing_ward_patient，uk_ward_patient_visit 单行）；幂等双层：eventId 构件幂等
 * （IdempotentConsumerSupport 三段式）+ uk_execution_plan 数据库硬防重（ON CONFLICT DO
 * NOTHING——重复 planNo 零副作用）。摆药挂接路（dispense 回填/INFUSION 升格）归 Task 6。
 *
 * <p>线程安全：无状态 singleton；写操作 @Transactional 收口（实现侧）。
 *
 * <p>配对纪律（宪法 A.4.3-20）：主表 order_execution 与实现侧
 * {@code ServiceImpl<OrderExecutionMapper, OrderExecution>} 配对，接口侧收拢
 * {@code extends IService<OrderExecution>}——主表通用 CRUD 直接复用 IService 契约面；
 * 生成/撤销为带守卫链的自有方法承载（禁经 IService 通用面绕行——通用面不盖幂等插入与
 * CAS 谓词）。
 */
public interface IOrderExecutionService extends IService<OrderExecution> {

    /** 医嘱终态撤销类型：停嘱与作废两路共用同一撤销 CAS 面，kind 仅承载日志/留痕语义区分 */
    enum TerminalKind {

        /** 停嘱（inpatient.order.stopped：医生停嘱与转科自动停嘱共用发布面） */
        STOPPED,

        /** 作废（inpatient.order.cancelled：仅未产生执行的医嘱可作废） */
        CANCELLED
    }

    /**
     * 转抄临时单生成（inpatient.order.transferred 消费体）：医嘱类型快照 + 临时单一次落成
     * ——exec_item 落快照占位（itemCode=m04 单号、itemName=transferType 类型快照，明细核对时
     * 从 dispense 行回填），m04_plan_no NULL（临时单不受 uk_execution_plan 约束，PG 唯一索引
     * 对 NULL 互异），plan_time=转抄时点。该行同时是后续计划生成（onPlanGenerated）的类型
     * 快照载体——计划载荷不携类型，过滤依据全在本行。类型过滤红线：transferType ∈
     * {blood, surgery, exam} 直接 return 零落单（执行载体归输血核对单/手术/检查申请流程）。
     * ward_id/bed_no 自病区患者投影回填（ward_id NOT NULL）；投影缺行（入科事件未达/未登记）
     * warn+跳过，不以空值落单。
     *
     * @param m04OrderNo    M04 医嘱号，非空；来源：事件载荷
     * @param visitId       住院就诊号（I 型 14 位），非空；来源：事件载荷
     * @param patientId     患者主索引，正数；来源：事件载荷
     * @param transferType  转抄医嘱类型（OrderType 子键小写：drug/lab/exam/surgery/blood/
     *                      nursing/diet/consult/discharge-med），非空；来源：事件载荷
     * @param transferredAt 转抄时点（UTC 语义），非空；来源：事件载荷 firstTransferredAt
     */
    void onOrderTransferred(
            String m04OrderNo, String visitId, long patientId, String transferType, Instant transferredAt);

    /**
     * 计划批量生成（inpatient.order-plan.generated 消费体）：按转抄快照行（m04_plan_no NULL
     * 的类型快照载体）逐 planNo 落执行单——快照缺行=事件乱序（计划先于转抄到达）warn+跳过；
     * planTime=planDate+HH:mm 北京钟面组合（TimeConstants.HEALTHCARE_TZ，禁容器时区漂移）；
     * 每单经 insertIgnorePlanConflict 落库，重复 planNo 撞 uk_execution_plan 零副作用
     * （ON CONFLICT DO NOTHING）。execution_type 生成一律 GENERIC——M04 载荷无用法字段，
     * 静脉判定归 dispense 回填时点（升格 INFUSION 并建监测挂接，Task 6）。
     *
     * @param m04OrderNo M04 医嘱号，非空；来源：事件载荷
     * @param visitId    住院就诊号，非空；来源：事件载荷
     * @param patientId  患者主索引，正数；来源：事件载荷
     * @param planDate   计划日期（ISO yyyy-MM-dd；日切=次日、补偿=当日），非空；来源：事件载荷
     * @param planNos    本批计划号集（PL+yyyyMMdd+5 位流水），非空；来源：事件载荷，与 planTimes 下标对齐
     * @param planTimes  本批计划时点集（HH:mm 二十四小时制），非空；来源：事件载荷，与 planNos 下标对齐
     */
    void onPlanGenerated(
            String m04OrderNo,
            String visitId,
            long patientId,
            LocalDate planDate,
            List<String> planNos,
            List<String> planTimes);

    /**
     * 医嘱终态撤销（inpatient.order.stopped/cancelled 消费体）：该医嘱全部未执行态
     * （CREATED/SIGNED/CHECKED）执行单批量 CANCELLED 并落原因（casCancelBatch CAS）。
     * EXECUTING 不动——长期停嘱由 M04 计划侧联动，本侧仅撤未执行（Spec :126）。0 行=无未执行
     * 单或重复投递已撤销，幂等达成不构成失败。
     *
     * @param m04OrderNo M04 医嘱号，非空；来源：事件载荷
     * @param reason     撤销原因（停嘱理由/作废理由，留痕必填），非空；来源：事件载荷
     * @param kind       终态类型（STOPPED 停嘱 / CANCELLED 作废——日志语义区分，撤销面同款），非空
     */
    void onOrderTerminal(String m04OrderNo, String reason, TerminalKind kind);
}
