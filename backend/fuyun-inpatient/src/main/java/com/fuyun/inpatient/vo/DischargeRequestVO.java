package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.DischargeRequest;
import java.time.OffsetDateTime;

/**
 * 出院申请出参（申请/取消/确认三端点共用回显面）：预审状态与欠费额为 billing 权威数据的
 * 快照回显（GC18——金额只出不进，工作站不传金额）；结算完成时点与挂账审批单号为放行
 * 双条件的回显锚。清理结果明细（追踪清单）经 ClearanceVO 专列出参。
 *
 * @param requestNo              出院申请单号（DC+yyyyMMdd+5 位流水），非空
 * @param visitId                住院就诊号（I 型 14 位；表内存主键出参转号），非空
 * @param dischargeWay           离院方式（病案首页代码），非空
 * @param expectDischargeAt      预出院时间，可空（历史行防御）
 * @param requestedAt            申请时点，非空
 * @param requesterId            申请医生（员工 ID string），非空
 * @param status                 申请状态（DischargeRequestStatus 五值），非空
 * @param arrearsAmount          欠费额快照（分；BLOCKED 时非空，READY/结清后为 null），可空
 * @param settlementCompletedAt  出院结算完成时点（双条件回显锚；未结算为 null），可空
 * @param approvalNo             挂账审批单号（BLOCKED→READY 放行凭证；未走挂账为 null），可空
 */
public record DischargeRequestVO(
        String requestNo,
        String visitId,
        String dischargeWay,
        OffsetDateTime expectDischargeAt,
        OffsetDateTime requestedAt,
        String requesterId,
        String status,
        Long arrearsAmount,
        OffsetDateTime settlementCompletedAt,
        String approvalNo) {

    /**
     * 实体行 → 出参映射（visitId 号转写：表内存主键 → I 型 14 位号）。
     *
     * @param row     出院申请实体行，非空
     * @param visitNo 住院就诊号（I 型 14 位，调用方已取数），非空
     * @return 出院申请出参，非空
     */
    public static DischargeRequestVO from(DischargeRequest row, String visitNo) {
        return new DischargeRequestVO(
                row.getRequestNo(),
                visitNo,
                row.getDischargeWay(),
                row.getExpectDischargeAt(),
                row.getRequestedAt(),
                row.getRequesterId(),
                row.getStatus(),
                row.getArrearsAmount(),
                row.getSettlementCompletedAt(),
                row.getApprovalNo());
    }
}
