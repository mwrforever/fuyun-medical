package com.fuyun.outpatient.service;

import com.fuyun.billing.api.RefundApprovedPayload;
import com.fuyun.outpatient.api.AppointmentTimeoutPayload;
import com.fuyun.outpatient.dto.AppointmentCreateRequest;
import com.fuyun.outpatient.dto.RescheduleRequest;
import com.fuyun.outpatient.vo.AppointmentVO;
import com.fuyun.outpatient.vo.ApptCreditVO;
import com.fuyun.outpatient.vo.VisitVO;
import java.util.List;

/**
 * 预约/当日挂号服务（M03 FU-M03-02/03 写路径唯一入口）：统一预约主流程七步（患者归一冻结拦截→
 * 爽约限约→限购→池行复核→Redis 预扣→appointment 落库+池行 CAS→渠道分流占位/直达 TAKEN）、预约取号、
 * 支付超时释放（延迟队列消费业务面）、退号退费联动四分支（Task 6）、改期先占新后退旧与爽约信用
 * 管理面。聚合型服务不继承 IService（A.4.3-20）。
 */
public interface IAppointmentService {

    /**
     * 统一预约/当日挂号。
     *
     * @param request 预约请求（patientId/poolId/channel），非空；契约校验由 @Valid 承载
     * @return 预约单出参（窗口/自助直达 TAKEN 携 visit_id；portal 为 RESERVED+payDeadline），非空
     * @throws com.fuyun.common.exception.BizException OP-1002（404 号源池不存在）/ OP-1003（409 号源不足）/
     *                                                 OP-1004（409 停诊或排班状态违例）/ OP-1005（409 同日同科限购）/
     *                                                 OP-1006（409 爽约限约期内）/ OP-1007（409 患者冻结拦截）/
     *                                                 OP-1019（400 渠道词表外或 P1 未开放）时触发；
     *                                                 建议处理策略：按 errorCode 提示用户
     */
    AppointmentVO book(AppointmentCreateRequest request);

    /**
     * 预约取号（RESERVED→TAKEN，同事务签发 visit 并删支付占位键）。
     *
     * @param apptNo 预约单业务号，非空；来源：窗口/自助扫码或输入
     * @return 就诊记录出参（REGISTERED），非空；TAKEN 态重复取号幂等返回既有 visit
     * @throws com.fuyun.common.exception.BizException OP-1008（409 支付时限已过）/
     *                                                 OP-1009（409 预约单不存在或状态不允许取号）时触发；
     *                                                 建议处理策略：超时单引导窗口人工处置
     */
    VisitVO take(String apptNo);

    /**
     * 退号（退号退费联动四分支，Task 6；裁决 7——退费一律经 OutpatientBillingPort 免审档，终态
     * 一律 billing.refund.approved 回执后置）：①支付时限内未支付/无结算锚→直接取消+号源回池+
     * cancelled 事件（feeRefundTriggered=false）；②已支付未取号→applyRefund 后保持占位待回执；
     * ③已取号未报到→同分支 2 退费链，visit 回滚随回执；④已报到/已接诊→OP-1010 拒线上退
     * （窗口人工「未诊即退」线下承载，Spec :137）。
     *
     * @param apptNo 预约单业务号，非空；来源：工作站/portal 退号入口
     * @param reason 退号原因，非空白；来源：操作者/患者录入（事件 reason 组件与审计留痕同源）
     * @return 预约单出参（分支 1=CANCELLED；分支 2/3=原态占位待回执），非空
     * @throws com.fuyun.common.exception.BizException OP-1009（409 预约单不存在或终态不可退）/
     *                                                 OP-1010（409 线上退号时限外或已报到不可线上退）/
     *                                                 M13 退费守卫（BILL-*，经端口原样透传）时触发；
     *                                                 建议处理策略：时限外单引导窗口办理
     */
    AppointmentVO cancel(String apptNo, String reason);

    /**
     * 改期（退旧号新，reschedule_of 链；先占新后退旧防两头空，Spec :137）：新池行全套预扣+CAS+
     * 新 appointment 行（RESERVED、reschedule_of=旧单号），成功后旧单 CAS→CANCELLED+回池+删占位键；
     * 任一步失败整体回滚（事务），成功发布 appointment.rescheduled 链事件。改期仅承载未支付占位
     * 迁移（已支付单走退号退费链，资金无涉红线）。
     *
     * @param apptNo  旧预约单业务号，非空；来源：工作站/portal 改期入口
     * @param request 改期请求（newPoolId），非空；来源：可约号源查询选行
     * @return 新预约单出参（RESERVED），非空
     * @throws com.fuyun.common.exception.BizException OP-1002（404 新池不存在）/ OP-1003（409 新池号源不足）/
     *                                                 OP-1004（409 新池停诊或排班违例）/ OP-1005（409 改期目标
     *                                                 与既有有效单限购冲突）/ OP-1009（409 旧单不存在或
     *                                                 非 RESERVED/已支付）时触发；建议处理策略：
     *                                                 已支付单先退号退费后重新预约
     */
    AppointmentVO reschedule(String apptNo, RescheduleRequest request);

    /**
     * 退费回执消费业务（billing.refund.approved → 退号终态，appointment 分支）：按
     * appointment.fee_settlement_id=payload.settlementId() 定位 RESERVED/TAKEN 单→CAS 置 CANCELLED
     * （TAKEN 态同步 visit REGISTERED→CANCELLED+迁移日志+visit.cancelled 事件）+号源回池+
     * fee_status=REFUNDED+cancelled 事件（feeRefundTriggered=true）。无命中/并发落败幂等跳过。
     *
     * @param payload 退费回执载荷（V605 id 20 七组件），非空；来源：OutpatientRefundApprovedListener 解析
     * @throws IllegalStateException 回执驱动链路的数据异常（visit 按回填锚定位失败等，交死信留痕）
     */
    void confirmRefundedCancel(RefundApprovedPayload payload);

    /**
     * 挂号费收费回填（Task 10 settlement.completed 消费侧分发）：挂号费与就诊费同 visit 结算面
     * （收费工作台直调 M13 划价/结算 REST，裁决 7——M03 零收费 REST 零资金逻辑），结算回执经
     * visit 锚定位 UNPAID 预约单后 CAS 回填 fee_status=PAID + fee_settlement_id（退号退费定位
     * 锚）。PAID⇒visit 锚在位为可实现不变式（billing 结算面以 visit_id 为 NOT NULL 硬锚，取号
     * casTake 先行回填——未取号占位单不产生挂号费结算，Task 6 契约缝定案①）。无命中/并发落败
     * 幂等跳过（回执可重投，终态幂等收敛）。
     *
     * @param settleNo     结算编号（日志留痕锚点），非空；来源：settlement.completed 载荷
     * @param settlementId 结算单 id（fee_settlement_id 回填值），非空非零；来源：同上
     * @param visitId      CF-3 就诊号（预约单 visit 锚，casTake 回填），非空；来源：同上
     */
    void markRegistrationPaid(String settleNo, long settlementId, String visitId);

    /**
     * 支付超时释放（延迟队列消费业务面，消费幂等三段式之外的业务态幂等守卫）：RESERVED→NO_SHOW CAS
     * 影响 1 行才执行释放面（version 条件回池+Redis 回补+删占位键+爽约信用记录）；0 行=已取号/已取消/
     * 已释放，幂等跳过禁二次释放。
     *
     * @param payload 超时回调载荷（apptNo/patientId/poolId），非空；来源：超时监听器解析
     * @throws IllegalStateException 预约单按 appt_no 定位失败时触发（数据异常，交容器拒收进死信留痕）
     */
    void markTimeout(AppointmentTimeoutPayload payload);

    /**
     * 爽约信用记录查询（按患者维度，id 降序最新在前）：工作站信用管理列表消费面。
     *
     * @param patientId 患者主索引，非空；来源：工作站查询入参
     * @return 信用记录出参列表（id 降序）；无记录返回空列表
     */
    List<ApptCreditVO> creditsByPatient(long patientId);

    /**
     * 爽约限约手工解除（Task 6）：restrict_to 提前至今日-1（即时失效）+release_reason 留痕
     * （@AuditLog WRITE 端点承载审计，updated_by 取操作者上下文）。
     *
     * @param id     信用记录主键，非空；来源：信用列表选行
     * @param reason 解除理由，非空白；来源：管理员录入
     * @return 解除后的信用记录出参，非空
     * @throws com.fuyun.common.exception.BizException OP-1009（404 记录不存在 / 409 无在效限约区间）
     *                                                 时触发；建议处理策略：核实记录状态后操作
     */
    ApptCreditVO releaseCredit(long id, String reason);
}
