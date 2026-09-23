-- V800：CF-6 契约提前冻结载体与 M05 发布事件登记（P1 计划 :92「CF-6 提前冻结（仅契约登记）」）
--
-- 位置说明：CF-6 的 M04 事件族与 M06 审方回流事件在生产侧属 inpatient/pharmacy，但 M04/M06 的
--   住院面实装归 P2，且 fuyun-inpatient 未入 fuyun-app 依赖（其迁移不在应用 classpath）；
--   本 PR 由 M05 承担 CF-6 冻结责任，种子落 nursing 段并操作 integration.event_registry
--   （先例：V105 种子落发布方目录、V204 UPDATE V605 数据行）。
-- 升级路径：M04/M06 P2 实装时以彼时更高版本迁移 UPDATE 本批 desc 升级字段级契约（禁改本文件）。
-- id 排定：41–64（全局递增，当前最大 40）；status 一律 ACTIVE（词表 ACTIVE/DEPRECATED，V2:13）。
-- 三方一致红线：本文件 desc ↔ NursingMessagingConstants 字面量 ↔ nursing/api 载荷 record 组件名。
-- 禁敏感明文：全部 desc 不含患者姓名/证件等敏感字段明文。

-- ===================== 一、CF-6 冻结载体：M04 医嘱状态机与就诊/床位事件族（id 41–52） =====================
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 41, 'inpatient.order.audited', 'inpatient',
       '医嘱审核通过：m04OrderNo/visitId/patientId/auditType/auditOperator/auditedAt；类型子键路由 drug|lab|exam|surgery|blood|diet|consult|discharge-med（登记名不带子键，R3-06）；nursing 类医嘱不经本子键分发（经 transferred/order-plan.generated 链进入 M05）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.audited' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 42, 'inpatient.order.transferred', 'inpatient',
       '医嘱转抄：m04OrderNo/visitId/patientId/transferType/firstTransferredAt；M05 据此生成临时医嘱单次执行单（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.transferred' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 43, 'inpatient.order-plan.generated', 'inpatient',
       '长期医嘱计划拆分：m04OrderNo/visitId/patientId/planDate/planNos[]/planTimes[]；M05 据此批量生成次日执行单（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order-plan.generated' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 44, 'inpatient.order.stopped', 'inpatient',
       '医嘱停止：m04OrderNo/visitId/patientId/stoppedAt/stopOperator/stopReason；M05 撤销未执行执行单（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.stopped' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 45, 'inpatient.order.cancelled', 'inpatient',
       '医嘱作废：m04OrderNo/visitId/patientId/cancelledAt/cancelReason；M05 撤销未执行执行单并拦截在途核对（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.cancelled' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 46, 'inpatient.order.revoked', 'inpatient',
       '医嘱撤回（转抄前）：m04OrderNo/visitId/patientId/revokedAt；M05 不订阅（转抄前无执行单，R3-07 死订阅已删），仅登记保证契约完整（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.revoked' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 47, 'inpatient.order.executed', 'inpatient',
       '医嘱执行回签聚合完成：m04OrderNo/visitId/patientId/planNo/executedAt；M13 据此确认住院费用（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order.executed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 48, 'inpatient.visit.admitted', 'inpatient',
       '患者入科：visitId/patientId/wardId/bedId/admittedAt/nursingLevel；M05 维护病区患者本地视图（P2 事件链）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.visit.admitted' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 49, 'inpatient.visit.transferred', 'inpatient',
       '患者转科/转床：visitId/patientId/fromWardId/fromBedId/toWardId/toBedId/transferredAt；M05 病区视图变更与未执行执行单重定向（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.visit.transferred' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 50, 'inpatient.visit.discharge-requested', 'inpatient',
       '出院申请：visitId/patientId/requestedAt；M05 清退在途任务提示（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.visit.discharge-requested' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 51, 'inpatient.visit.discharged', 'inpatient',
       '患者出院终态：visitId/patientId/dischargedAt；M05 终清在途任务与执行单（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.visit.discharged' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 52, 'inpatient.bed.changed', 'inpatient',
       '床位动态变更：wardId/bedId/bedNo/bedStatus/patientId；M05 护士站一览与大屏床位动态（P2）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.bed.changed' AND deleted = 0);

-- ===================== 二、CF-6 冻结载体：M06 住院医嘱审方结论回流（B-6，id 53–54） =====================
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 53, 'pharmacy.medication-order.audit-completed', 'pharmacy',
       '住院医嘱审方通过：target=m04_order_no/auditNo/auditOperator/auditedAt；M04 消费置医嘱可执行（B-6，90 号文档统一裁决）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.medication-order.audit-completed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 54, 'pharmacy.medication-order.audit-rejected', 'pharmacy',
       '住院医嘱审方驳回：target=m04_order_no/auditNo/rejectReason/auditOperator/auditedAt；M04 消费置医嘱驳回态（B-6）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.medication-order.audit-rejected' AND deleted = 0);

-- ===================== 三、CF-6 冻结载体：执行回签 API 契约（约定名占行，id 55） =====================
-- 形态先例：CF-1 信封约定行 / CF-7 遥测消息模型行（integration/V5:5-7）——非事件式契约以约定名占一行。
-- 字段级请求/响应契约由 M04 P2 Spec 定稿时以更高版本迁移 UPDATE 本行 desc（禁在此虚构字段）。
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 55, 'inpatient.order-plan.execute-confirm', 'inpatient',
       '执行回签 API 契约（CF-6）：POST /api/v1/inpatient/order-plans/{no}/execute-confirm；语义=长期医嘱首个回签 plan PENDING→EXECUTED 且医嘱头 TRANSFERRED→EXECUTING / 临时医嘱单次回签医嘱头 TRANSFERRED→COMPLETED / 全部计划终态 EXECUTING→COMPLETED，并发布 inpatient.order.executed；调用方 M05（主路径进程内同步调用，辅路径 nursing.order-execution.completed 事件对账，双路到达以 M04 计划唯一约束幂等仅计一次）；**字段级契约 pending M04 P2 定稿——本行暂不可作为完整契约引用**；升级义务已挂 M04 侧 P2 交付清单（04-inpatient Spec 注记 + TASK.md 工单，双侧留痕）（CF-6 冻结载体）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'inpatient.order-plan.execute-confirm' AND deleted = 0);

-- ===================== 四、M05 发布面：P1 实装五条（id 56–60） =====================
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 56, 'nursing.vital-sign.recorded', 'nursing',
       '体征记录转正入卡：patientId/visitId/measuredAt/source/reviewStatus/abnormal（abnormal=是否越正常范围，驱动观察行归集）；M09/M11/M19 可能取数（P1 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.vital-sign.recorded' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 57, 'nursing.assessment.completed', 'nursing',
       '护理评估完成：patientId/visitId/assessNo/scaleType/totalScore/riskLevel；高危联动防范任务（P1 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.assessment.completed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 58, 'nursing.task.created', 'nursing',
       '护理任务生成：taskNo/patientId/visitId/taskType/source；M14 联动规则与 M16 紧急呼叫经 POST /tasks 幂等创建（P1 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.task.created' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 59, 'nursing.task.completed', 'nursing',
       '护理任务完成/取消：taskNo/status（COMPLETED 或 CANCELLED）；回写关联单据（P1 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.task.completed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 60, 'nursing.shift.completed', 'nursing',
       '交接班完成：handoverNo/wardId/shiftCode/outgoingNurseId/incomingNurseId；M19 工作量统计取数（P1 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.shift.completed' AND deleted = 0);

-- ===================== 五、M05 发布面：P1 占位四条（id 61–64，P2 实装） =====================
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 61, 'nursing.task.overdue', 'nursing',
       '护理任务逾期升级动作广播：taskNo/patientId/wardId/planTime/escalationCount；P1 逾期为读时惰性判定不发布本事件，P2 由 delay.task-overdue 延迟队列驱动（P1 占位登记，P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.task.overdue' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 62, 'nursing.infusion.started', 'nursing',
       '开始输注：executionNo/patientId/visitId/bagLabelCode/startedAt；M14 订阅建立告警↔任务↔传感器关联（FU-M05-06 归 P2）（P1 占位登记，P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.infusion.started' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 63, 'nursing.infusion.completed', 'nursing',
       '拔针/输注结束：executionNo/patientId/visitId/endedAt；M14 停止监测、自动生成输液入量行（FU-M05-06 归 P2）（P1 占位登记，P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.infusion.completed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 64, 'nursing.order-execution.completed', 'nursing',
       '执行单执行回执（CF-6 双路对账辅路径）：executionNo/m04PlanNo/m04OrderNo/patientId/visitId/环节时点集/executorId/overrideFlag；M04 订阅与 execute-confirm 主路径双路对账（FU-M05-04 归 P2）（P1 占位登记，P2 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'nursing.order-execution.completed' AND deleted = 0);
