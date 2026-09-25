package com.fuyun.inpatient.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 在途清理与预审结果出参（GET /discharge-requests/{no}/clearance）——出院放行工作台取数面：
 * 清理三动作计数快照（长期停嘱数/临时追踪清单/计划作废数）+ 预审结果（状态+欠费额）+
 * 结算完成标记与挂账审批凭证，人工处置（追踪清单内无合法停嘱边的停留医嘱）据此跟进。
 * 追踪清单行仅定位键与状态（脱敏红线——禁患者姓名/诊断文本）。
 *
 * @param requestNo             出院申请单号，非空
 * @param visitId               住院就诊号（I 型 14 位），非空
 * @param status                申请状态（DischargeRequestStatus 五值），非空
 * @param stoppedLongCount      长期医嘱批量停嘱数（清理动作①），非负
 * @param trackedOrders         临时追踪清单（清理动作②——无合法停嘱边的停留医嘱，人工处置面），非空
 * @param cancelledPlanCount    未执行计划作废数（清理动作③），非负
 * @param arrearsAmount         欠费额快照（分；BLOCKED 时非空），可空
 * @param settlementCompletedAt 出院结算完成时点（双条件之一回显；未结算为 null），可空
 * @param approvalNo            挂账审批单号（BLOCKED→READY 放行凭证），可空
 */
public record ClearanceVO(
        String requestNo,
        String visitId,
        String status,
        int stoppedLongCount,
        List<TrackedOrderVO> trackedOrders,
        int cancelledPlanCount,
        Long arrearsAmount,
        OffsetDateTime settlementCompletedAt,
        String approvalNo) {

    /**
     * 追踪清单行（无合法停嘱边的停留医嘱定位面）：CREATED/AUDIT_REJECTED 态医嘱无合法停嘱边
     * （04 Spec §3.3 冻结），临时在途医嘱保留临床自然收敛——两类均入清单供人工处置
     * （作废/驳回重提/等待执行完成），不自动迁移状态。
     *
     * @param orderNo    医嘱号，非空
     * @param orderClass 医嘱分类（LONG/STAT），非空
     * @param status     医嘱状态（OrderStatus 八态——清理时点实态），非空
     */
    public record TrackedOrderVO(String orderNo, String orderClass, String status) {}
}
