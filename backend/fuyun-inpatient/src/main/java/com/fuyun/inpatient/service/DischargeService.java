package com.fuyun.inpatient.service;

import com.fuyun.inpatient.dto.DischargeConfirmRequest;
import com.fuyun.inpatient.dto.DischargeRequestCreate;
import com.fuyun.inpatient.vo.ClearanceVO;
import com.fuyun.inpatient.vo.DischargeRequestVO;
import java.time.Instant;

/**
 * 出院管理域服务（FU-M04-07，V907 两表业务面）——出院主链路门面：出院申请（单事务在途清理
 * 编排[长期医嘱批量停嘱→临时追踪清单→未执行计划作废]+费用预审[BillingAccountQueryPort 只读
 * 快照：结清 READY/欠费 BLOCKED 附欠费额]+visit→DISCHARGE_REQUESTED+discharge-requested
 * 事件）、取消出院（visit 回 ADMITTED 且长期医嘱不复活——需重新开立，Spec 边界）、离院确认
 * （GC19 三重前置校验：全部长期医嘱已终态+在途执行计划清零+预审 READY 且结算完成标记双条件
 * →visit DISCHARGED+床位 OCCUPIED→DISINFECTING+出院带药放行[audited.discharge-med 子键]+
 * 随访计划生成+discharged 事件）与 billing 两事件消费回执（结算完成标记/挂账审批放行，
 * BillingEventListener 委托承载，幂等三段式的业务体）。金额红线（04 Spec 红线 3）：本域只存
 * 预审状态与欠费额快照，DTO/请求面零金额输入。
 */
public interface DischargeService {

    /**
     * 出院申请（POST /visits/{visitId}/discharge-request，「预出院/明日出院」模式）：
     * 守卫（就诊在院 ADMITTED/离院方式词表）→ 在途清理编排三动作（①长期医嘱批量停嘱——
     * 复用 MedicalOrderService.stopAllForTransfer 停嘱面[reason=出院]；②无合法停嘱边的停留
     * 医嘱[CREATED/AUDIT_REJECTED]与临时在途医嘱逐条入清理结果追踪清单供人工处置；
     * ③未执行计划全量作废）→ 费用预审（BillingAccountQueryPort.precheck：结清→READY /
     * 欠费→BLOCKED 附欠费额快照）→ visit CAS DISCHARGE_REQUESTED + 申请行落库
     * （uk_visit_active 兜底一 visit 至多一条在途申请）→ 事务内发布
     * inpatient.visit.discharge-requested（V800 id 50 载荷）。
     *
     * @param visitId 住院就诊号（I 型 14 位），非空；来源：路径参数
     * @param req     申请入参（预出院时间/离院方式），非空；来源：医生站出院申请单
     * @return 申请出参（status=预审后实态 READY/BLOCKED，欠费额快照回显），非空
     * @throws com.fuyun.common.exception.BizException IP-1007（404 就诊不存在）/IP-1008（409
     *                 非在院禁申请——出院申请限 ADMITTED 态）/IP-1022（400 离院方式词表外/
     *                 操作者标识非数字）/IP-1023（409 在途申请唯一冲突——并发重复申请）/
     *                 IP-1010（409 停嘱链状态机违例——并发迁移窗口整体回滚）
     */
    DischargeRequestVO createRequest(String visitId, DischargeRequestCreate req);

    /**
     * 取消出院申请（POST /discharge-requests/{no}/cancel）：仅 REQUESTED 态可取消（READY/
     * BLOCKED 须先经业务裁决，Spec §5 状态机冻结边）；visit 回 ADMITTED；<b>长期医嘱不复活
     * ——停嘱为终态迁移，恢复治疗须重新开立</b>（Spec 测试边界）；被追踪清单收录的停留医嘱
     * 状态面零变更（清理只记追踪不迁移）。取消不发事件（登记面无对应事件契约）。
     *
     * @param requestNo 出院申请单号，非空；来源：路径参数
     * @return 取消后出参（status=CANCELLED），非空
     * @throws com.fuyun.common.exception.BizException IP-1018（404 申请不存在）/IP-1017（409
     *                 非 REQUESTED 态禁取消）/IP-1022（400 操作者标识非数字）/IP-1023（409
     *                 visit 态并发迁移——取消 CAS 零行）
     */
    DischargeRequestVO cancel(String requestNo);

    /**
     * 在途清理与预审结果查询（GET /discharge-requests/{no}/clearance）：清理三动作计数快照
     * （长期停嘱数/追踪清单/计划作废数）+ 预审状态与欠费额 + 结算完成标记与挂账审批凭证——
     * 出院放行工作台与人工处置（追踪清单内停留医嘱）取数面。
     *
     * @param requestNo 出院申请单号，非空；来源：路径参数
     * @return 清理与预审结果出参，非空
     * @throws com.fuyun.common.exception.BizException IP-1018（404 申请不存在）/IP-1007（404
     *                 关联就诊不存在——数据不一致防御）
     */
    ClearanceVO clearance(String requestNo);

    /**
     * 离院确认（POST /discharge-requests/{no}/confirm，GC19 离院前置校验红线承载）：
     * 三重前置校验（①全部长期医嘱已终态——非终态长期医嘱存在即拒；②在途执行计划清零
     * ——PENDING 计划存在即拒；③预审 READY 且 settlement_completed_at 已落——单条件不满足
     * 均抛 IP-1017）→ 出院带药放行（DISCHARGE_MED 类 CREATED 医嘱经状态机迁 AUDITED +
     * SYSTEM 审计行 + inpatient.order.audited.discharge-med 子键事件——M06 撮此摆药）→
     * visit CAS DISCHARGED（离院方式誊写）+ 申请 CAS COMPLETED → 床位 OCCUPIED→DISINFECTING
     * 终末消毒流转（bed_assign 闭合）→ 随访计划生成（plan_date=出院日后 N 日参数）→ 事务内
     * 发布 inpatient.visit.discharged（V800 id 51 载荷）。任一步失败异常传播整体回滚。
     *
     * @param requestNo 出院申请单号，非空；来源：路径参数
     * @param req       确认入参（随访三参数，全部可选缺省 7 日/电话/「出院随访」），非空
     * @return 确认后出参（status=COMPLETED），非空
     * @throws com.fuyun.common.exception.BizException IP-1018（404 申请不存在）/IP-1017（409
     *                 放行条件未满足——三重校验任一不过/非 READY 态/未结算）/IP-1022（400
     *                 操作者标识非数字/随访参数词表外）/IP-1023（409 visit/申请 CAS 并发
     *                 零行）/IP-1010（409 带药放行状态机违例——并发迁移窗口）
     */
    DischargeRequestVO confirm(String requestNo, DischargeConfirmRequest req);

    /**
     * 结算完成回执消费体（billing.settlement.completed 载荷 visitId，结算类型=IN 出院结算
     * 分支；BillingEventListener 委托）：visitId 反查在途申请行落 settlement_completed_at
     * 标记（IS NULL 限定 CAS 幂等——重复投递/无在途申请零行 info 留痕直返）。
     *
     * @param visitId   CF-3 住院就诊号（I 型 14 位，载荷原值），非空；来源：billing 事件载荷
     * @param settledAt 结算完成时点（信封 occurredAt 回执时点），非空；来源：billing 事件信封
     */
    void onSettlementCompleted(String visitId, Instant settledAt);

    /**
     * 挂账审批放行回执消费体（billing.arrears.approved 载荷 visitId/approvalNo，
     * BillingEventListener 委托）：BLOCKED 态在途申请 CAS 转 READY 并记录审批单号（放行
     * 凭证留痕；非 BLOCKED 态零行 info 留痕——已达 READY 幂等直返）。
     *
     * @param visitId    CF-3 住院就诊号（I 型 14 位，载荷原值），非空；来源：billing 事件载荷
     * @param approvalNo 挂账审批单号（放行凭证），非空；来源：billing 事件载荷
     */
    void onArrearsApproved(String visitId, String approvalNo);
}
