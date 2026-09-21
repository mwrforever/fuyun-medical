package com.fuyun.outpatient.service;

import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.billing.api.SettlementCompletedPayload;
import com.fuyun.pharmacy.api.PrescriptionCancelledPayload;

/**
 * 门诊收费编排服务（M03 FU-M03-07/08，Task 10 消费侧唯一业务面；聚合型服务不继承 IService，
 * A.4.3-20）：settlement.completed 单据精确放行扇出（经 billing SettlementQueryPort 反查
 * orderRefs/rxRefs 精确定位——禁 visit 全量扫描，申请单 PENDING_FEE→CHARGED+结算锚回填、处方
 * 引用行 CREATED→CHARGED，扇出 order.charged 驱动 M06/M07/M08/M05 放行）+ refund.approved
 * order 分支回滚（CHARGED→CANCELLED 逆向+order.cancelled 逐单终态扇出，回执驱动逆向仅在回执后，
 * Spec :119）+ 处方作废/发药回执回流（RX_REF 引用行作废联动与「已发药」派生镜像）。资金无涉
 * 红线（裁决 7）：零金额逻辑零资金动作，资金面一律 M13 权威；refund.approved 的 appointment
 * 分支终态归 {@link IAppointmentService#confirmRefundedCancel}，本服务仅承载 order 分支。
 * 事务边界：消费业务体方法级 @Transactional（单据/引用行/visit 推进同事务原子；事件发布走
 * 事务内 publishEvent→AFTER_COMMIT 出 MQ，A.4.2-7）。
 */
public interface IChargingService {

    /**
     * 结算完成消费业务（billing.settlement.completed，V605 id 19；监听器委托入口）：OUT/门诊结算
     * 类型守卫（IN 为住院分支忽略）→载荷锚守卫（settlementId/settleNo/visitId/patientId 缺失
     * 可读拒绝进死信留痕）→0 元/负额结算拒绝（W-20 两问澄清前沿用既有 400 拒绝语义，不放开不
     * 收窄）→visit 存在性校验→端口反查单据精确清单（端口异常转译可读拒绝）→单据/引用行放行
     * CAS→order.charged 扇出（有放行增量才发布）→visit 回诊推进→挂号费收费回填分发。重投幂等
     * （CAS 0 行重读定性跳过，二次投递零写零发布）。
     *
     * @param payload 结算完成载荷（V605 id 19 十组件），非空；来源：OutpatientSettlementCompletedListener
     *                解析
     * @throws IllegalStateException 载荷锚缺失/0 元组合越界/visit 缺失（数据异常）或端口调用异常
     *                               （转译）时触发——死信留痕人工对账，禁静默消费
     */
    void onSettlementCompleted(SettlementCompletedPayload payload);

    /**
     * 退费回执 order 分支消费业务（billing.refund.approved，V605 id 20；Task 6 appointment 分支
     * 之后追加，监听器委托入口）：端口反查结算单来源单据清单→orderRefs 逐单 CHARGED→CANCELLED
     * 逆向（CHARGED/IN_EXECUTION 逆向仅在回执后，Spec :119）→逐单发布 order.cancelled
     * （reason=退费逆向终态确认；rxNos=结算单处方清单，M06 未发药作废/退药收敛以此为准，本模块
     * 不直改 pharmacy 表）。无命中/非 CHARGED/并发落败幂等跳过（零发布）。
     *
     * @param payload 退费回执载荷（V605 id 20 七组件），非空；来源：OutpatientRefundApprovedListener
     *                解析
     * @throws IllegalStateException 端口反查异常（转译为可读拒绝）时触发——死信留痕人工对账
     */
    void onRefundApproved(RefundApprovedPayload payload);

    /**
     * 处方作废回流消费业务（pharmacy.prescription.cancelled，V702 id 26；监听器委托入口）：
     * RX_REF 引用行（ext_ref=rxNo）按当前态 CAS 至 CANCELLED（CREATED/PENDING_FEE/CHARGED 三态
     * 皆可迁——结算完成前后作废的引用行与②b 终态衔接，Spec :119 R2-10 回流驱动）。0 行幂等跳过
     * （重复回流/引用行缺失）。
     *
     * @param payload 处方作废载荷（五组件），非空；来源：OutpatientPrescriptionCancelledListener 解析
     * @throws IllegalStateException 载荷缺 rxNo 锚（不合规帧）时触发——死信留痕
     */
    void onPrescriptionCancelled(PrescriptionCancelledPayload payload);

    /**
     * 发药完成回流消费业务（pharmacy.dispense.completed，V702 id 28；监听器委托入口）：RX_REF
     * 引用行写「已发药」派生镜像 dispense_status=DISPENSED（引用行状态机五值不变，医生站/患者端
     * 可见已发药，Spec :142）。空档 CAS（乱序投递下退药镜像先至不被覆盖），0 行幂等跳过。
     *
     * @param rxNo       处方号（RX_REF 引用行 ext_ref 锚），非空；来源：OutpatientDispenseCompletedListener
     *                   载荷解析
     * @param dispenseNo 发药单号（日志留痕锚），非空；来源：同上
     * @throws IllegalStateException rxNo 空白（不合规帧）时触发——死信留痕
     */
    void onDispenseCompleted(String rxNo, String dispenseNo);

    /**
     * 退药受理回流消费业务（pharmacy.dispense.returned，V702 id 29；监听器委托入口）：RX_REF
     * 引用行镜像按 fullReturn 迁移（整单=FULL_RETURNED/部分=PART_RETURNED），单调不回退
     * （FULL_RETURNED 后零回写）。0 行幂等跳过（同值重投/终态后回写）。
     *
     * @param rxNo       处方号（RX_REF 引用行 ext_ref 锚），非空；来源：OutpatientDispenseReturnedListener
     *                   载荷解析
     * @param fullReturn 是否整单退药；来源：事件载荷 fullReturn 组件
     * @param dispenseNo 发药单号（日志留痕锚），非空；来源：事件载荷
     * @throws IllegalStateException rxNo 空白（不合规帧）时触发——死信留痕
     */
    void onDispenseReturned(String rxNo, boolean fullReturn, String dispenseNo);
}
