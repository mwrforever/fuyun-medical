-- V901：M04 住院域事件契约升级与补登（P2 PR-1 Task 2；inpatient 固定百位段 V900–V999 首批，
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--
-- W-33 闭合：UPDATE id 55 执行回签 API 契约为字段级契约定稿——替代 V800 pending 占位语义
--   （数据契约演进非 DDL 变更，V702 UPDATE V605 id 24 先例；V800 文件禁改）。
-- 新增 id 65–72 八行：M04 发布面 P2 实装事件族（入院登记/医嘱开立/医嘱审核驳回/会诊五态），
--   全局递增接 nursing 段最大 id 64；status 一律 ACTIVE（词表 ACTIVE/DEPRECATED，V2:13）。
-- 三方一致红线：本文件 desc ↔ InpatientMessagingConstants 字面量 ↔ inpatient/api/payload
--   载荷 record 组件名逐字同源（契约锚 InpatientMessagingContractTest），单向漂移即红灯。
-- 幂等形态：INSERT...SELECT 存在性守卫（V800/V105 先例）；全部 desc 禁敏感明文。

-- ===================== 一、W-33 闭合：id 55 执行回签 API 契约字段级定稿（UPDATE，不增行） =====================
UPDATE integration.event_registry
SET payload_desc = '执行回签 API 契约（CF-6）：POST /api/v1/inpatient/order-plans/{no}/execute-confirm；请求字段：executorId(long,必填,执行护士员工ID)/executedAt(缺省服务器时间)/routeCheckResult(可选,给药途径核对结论)；响应字段：planNo/m04OrderNo/orderStatus(迁移后医嘱头状态)/planStatus(迁移后计划状态)；三态迁移语义=长期医嘱首个回签 plan PENDING→EXECUTED 且医嘱头 TRANSFERRED→EXECUTING / 临时医嘱单次回签医嘱头 TRANSFERRED→COMPLETED / 全部计划实例终态 EXECUTING→COMPLETED，并发布 inpatient.order.executed；幂等=重复回签已 EXECUTED 计划返回当前状态、不迁移不发事件（计划行状态 CAS 兜底）；调用方 M05（主路径进程内同步调用，辅路径 nursing.order-execution.completed 事件对账，双路到达仅计一次）（W-33 闭合，P2 PR-1 定稿）',
    updated_at = now()
WHERE id = 55 AND event_type = 'inpatient.order-plan.execute-confirm';

-- ===================== 二、M04 发布面：入院/医嘱开立/审核驳回（id 65–67） =====================
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 65, 'inpatient.visit.registered', 'inpatient',
       '入院登记：visitId/patientId/admissionNo/registeredAt/insuranceType；M13 医保入院办理登记依据（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.visit.registered' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 66, 'inpatient.order.created', 'inpatient',
       '医嘱开立：m04OrderNo/visitId/patientId/orderType/orderClass/standbyFlag/groupNo/freqCode/items[]（itemSeq/itemCode/itemName/dosage/unit/route/quantity/itemType）；routing key 携带类型子键（drug 子键→M06 审方任务生成）；M06 消费 drug 子键（P2 薄切片）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.created' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 67, 'inpatient.order.audit-rejected', 'inpatient',
       '医嘱审核驳回：m04OrderNo/visitId/patientId/rejectReason/rejectedAt；M06 回执驱动，医生站修改重提路径（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.audit-rejected' AND deleted = 0);

-- ===================== 三、M04 发布面：会诊五态（id 68–72，五态共用 ConsultationPayload 按事件取用子集） =====================
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 68, 'inpatient.consultation.requested', 'inpatient',
       '会诊申请：consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/acceptedAt/completedAt/overdueAt/cancelledAt/reason；五态共用 ConsultationPayload 按事件取用子集（申请态取 consultNo~responseDeadline 与 reason）；急会诊 30min 时限经 responseDeadline 承载（读时惰性逾期判定）（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.consultation.requested' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 69, 'inpatient.consultation.accepted', 'inpatient',
       '会诊响应：consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/acceptedAt/completedAt/overdueAt/cancelledAt/reason；五态共用 ConsultationPayload 按事件取用子集（响应态追加 acceptedAt）（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.consultation.accepted' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 70, 'inpatient.consultation.completed', 'inpatient',
       '会诊完成：consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/acceptedAt/completedAt/overdueAt/cancelledAt/reason；五态共用 ConsultationPayload 按事件取用子集（完成态追加 completedAt）（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.consultation.completed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 71, 'inpatient.consultation.overdue', 'inpatient',
       '会诊超时升级动作：consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/acceptedAt/completedAt/overdueAt/cancelledAt/reason[状态停留 REQUESTED 仍可响应]；五态共用 ConsultationPayload 按事件取用子集（超时态取 overdueAt）；overdue 为动作广播非状态迁移（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.consultation.overdue' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 72, 'inpatient.consultation.cancelled', 'inpatient',
       '会诊取消：consultNo/visitId/patientId/fromDeptId/toDeptId/urgency/requestedAt/responseDeadline/acceptedAt/completedAt/overdueAt/cancelledAt/reason；五态共用 ConsultationPayload 按事件取用子集（取消态取 cancelledAt 与 reason）（P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.consultation.cancelled' AND deleted = 0);
