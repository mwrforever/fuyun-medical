package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 会诊五态共用事件载荷（inpatient.consultation.requested/accepted/completed/overdue/cancelled，
 * V901 id 68–72 冻结契约）：五事件共用一 record，按事件取用子集——申请取 consultNo~responseDeadline
 * 与 reason；响应追加 acceptedAt；完成追加 completedAt；超时取 overdueAt（动作广播非状态迁移，
 * 状态停留 REQUESTED 仍可响应）；取消取 cancelledAt 与 reason。未取用组件为 null。
 *
 * <p>科室标识承载口径：fromDeptId/toDeptId 为 M01 组织编码 string（V902 admission.target_dept_id/
 * inpatient_visit.current_dept_id 同款 VARCHAR(64) 形态——Task 11 落地实测修正，组件名与 V901
 * desc 逐字不变，契约锚 InpatientMessagingContractTest 不受影响）。钩子草稿未派单时 toDeptId
 * 可为 null（该面不发布事件，载荷仅在受邀科明确后的流转面出网）。
 *
 * @param consultNo        会诊单号，非空；来源：会诊域业务号
 * @param visitId          住院就诊号，非空
 * @param patientId        患者主索引，非空
 * @param fromDeptId       申请科室编码（M01 组织 code），非空；来源：就诊 current_dept_id
 * @param toDeptId         受邀科室编码（M01 组织 code），申请/响应/完成/超时面非空；钩子草稿
 *                         未派单面可为 null
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
        String fromDeptId,
        String toDeptId,
        String urgency,
        Instant requestedAt,
        Instant responseDeadline,
        Instant acceptedAt,
        Instant completedAt,
        Instant overdueAt,
        Instant cancelledAt,
        String reason) {}
