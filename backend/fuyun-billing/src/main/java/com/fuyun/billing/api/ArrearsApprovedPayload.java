package com.fuyun.billing.api;

import java.time.Instant;

/**
 * 挂账审批放行事件载荷（billing.arrears.approved，V1002 id 73；M04 出院费用拦截解除唯一驱动）：
 * 挂账审批通过（PENDING_APPROVAL→APPROVED）同事务发布，AFTER_COMMIT 出 fy.topic——
 * M04 消费将 discharge_request BLOCKED→READY（approval_no 放行凭证留痕）。脱敏红线：仅定位键
 * 与时间线/余额，禁患者姓名/诊断文本。
 *
 * @param visitId         CF-3 住院就诊号，非空；来源：审批单关联就诊行
 * @param approvalNo      审批单号（放行凭证），非空；来源：arrears_approval.approval_no
 * @param approvedAt      审批通过时点（UTC），非空；来源：审批动作时点
 * @param approvedBalance 审批时点押金余额快照（分），非空；来源：billing.deposit_account.balance 决出时点读数
 */
public record ArrearsApprovedPayload(String visitId, String approvalNo, Instant approvedAt, Long approvedBalance) {}
