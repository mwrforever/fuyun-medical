package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 会诊五态共用事件载荷（inpatient.consultation.requested/accepted/completed/overdue/cancelled，
 * V901 id 68–72 冻结契约）：五事件共用一 record，按事件取用子集——申请取 consultNo~responseDeadline
 * 与 reason；响应追加 acceptedAt；完成追加 completedAt；超时取 overdueAt（动作广播非状态迁移，
 * 状态停留 REQUESTED 仍可响应）；取消取 cancelledAt 与 reason。未取用组件为 null。
 *
 * @param consultNo        会诊单号，非空；来源：会诊域业务号
 * @param visitId          住院就诊号，非空
 * @param patientId        患者主索引，非空
 * @param fromDeptId       申请科室 ID，非空；来源：发起科室
 * @param toDeptId         受邀科室 ID，非空；来源：会诊申请受邀方
 * @param urgency          紧急度（急会诊/普通会诊字典值），非空；急会诊 30min 时限读时惰性判定
 * @param requestedAt      申请时点（UTC），非空
 * @param responseDeadline 响应截止时点（UTC），非空；急会诊时限承载锚
 * @param acceptedAt       受邀科接受时点（UTC），仅 accepted 态取用，其余为 null
 * @param completedAt      会诊完成时点（UTC），仅 completed 态取用，其余为 null
 * @param overdueAt        超时升级动作时点（UTC），仅 overdue 态取用，其余为 null
 * @param cancelledAt      取消时点（UTC），仅 cancelled 态取用，其余为 null
 * @param reason           申请/取消原因，可空——completed 等态不取用
 */
public record ConsultationPayload(
        String consultNo,
        String visitId,
        long patientId,
        long fromDeptId,
        long toDeptId,
        String urgency,
        Instant requestedAt,
        Instant responseDeadline,
        Instant acceptedAt,
        Instant completedAt,
        Instant overdueAt,
        Instant cancelledAt,
        String reason) {}
