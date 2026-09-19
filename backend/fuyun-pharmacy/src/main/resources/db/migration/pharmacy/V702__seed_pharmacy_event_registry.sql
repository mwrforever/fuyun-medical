-- V702：pharmacy 事件契约种子（CF-5 冻结载体；V605/V105 先例）。
-- id 24 = V605 占位行升级：本语句仅 UPDATE 数据行 payload_desc（V605 文件禁改——数据契约演进
--   非 DDL 变更，主控裁决 3），payload 与 api PrescriptionCreatedPayload record 组件逐字同源。
-- id 25–31 = 新登记（全局递增按迁移执行序，主控裁决 12）；id 25/31 为 outpatient 占位（producer
--   归 outpatient，正式字段随 PR-5 冻结）；id 27 无发布点（P3 审方引擎，禁发布）。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V5/V105/V605 先例）。

UPDATE integration.event_registry
SET payload_desc = '处方生效（CF-5 冻结，PR-4 实装，V605 占位升级）：prescriptionId/rxNo/visitId/patientId/lines[]{itemCode,quantity,usageSummary}（字段名与 api PrescriptionCreatedPayload record 组件逐字同源；prescriptionId=rx_no 业务号，billing sourceRef 直取）；M03 登记引用（PR-5 订阅）、M13(本仓 billing) 生成 PENDING 费用（PR-3 已订阅）；禁敏感明文'
WHERE id = 24 AND event_type = 'pharmacy.prescription.created';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 25, 'outpatient.order.charged', 'outpatient',
       '门诊缴费放行扇出（CF-5 占位，PR-4 pharmacy 订阅先行）：正式字段随 M03 实装（PR-5）冻结——发布方迁移若冲突以本行契约双向评审为准；PR-4 IT 以合成信封注入驱动放行（最小已知字段 orderId/visitId/patientId）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.charged');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 26, 'pharmacy.prescription.cancelled', 'pharmacy',
       '处方作废（PR-4 实装）：prescriptionId/rxNo/patientId/visitId/reason（与 api PrescriptionCancelledPayload record 组件逐字同源）；M03 引用状态联动（PR-5 订阅）；billing 不订阅——作废费用经进程内 PrescriptionFeePort 同事务承载',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.prescription.cancelled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 27, 'pharmacy.prescription.rejected', 'pharmacy',
       '审方驳回回执（占位登记，P3 审方引擎接入前无发布点，禁发布）：预期字段 rxNo/reason（药师意见必附，Spec §7）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.prescription.rejected');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 28, 'pharmacy.dispense.completed', 'pharmacy',
       '门诊发药完成（PR-4 实装）：dispenseNo/prescriptionId/rxNo/patientId/visitId/dispenseType/lines[]{itemCode,batchNo,quantity,traceCodes[]}（与 api DispenseCompletedPayload record 组件逐字同源）；M03 状态聚合（PR-5 订阅）、M13(本仓 billing) 执行占用标记 DISPENSED（PR-4 已接线）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.dispense.completed');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 29, 'pharmacy.dispense.returned', 'pharmacy',
       '退药受理完成（PR-4 实装）：dispenseNo/prescriptionId/rxNo/patientId/visitId/fullReturn/lines[]{itemCode,batchNo,quantity,traceCodes[]}（与 api DispenseReturnedPayload record 组件逐字同源）；M13(本仓 billing) 占用回退（fullReturn=true 回 NONE）、退费联动依据（Spec §3.5 先退药后退费）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.dispense.returned');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 30, 'pharmacy.drug.changed', 'pharmacy',
       '药品字典变更留痕广播（PR-4 实装）：drugId/drugCode/changeType(CREATE|UPDATE|MAPPING)（与 api DrugChangedPayload record 组件逐字同源）；broadcast 语义——消费方实时回查 GET /drugs/search 不做缓存订阅（R6-10）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.drug.changed');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 31, 'outpatient.order.cancelled', 'outpatient',
       '门诊退费逆向扇出（CF-5 占位，PR-4 pharmacy 订阅先行）：正式字段随 M03 实装（PR-5）冻结；本模块承担未发药作废与退药单终态确认（Spec §8 M03 边界 B-3 单向链），PR-4 登记消费+日志留痕、PR-5 回切终态确认',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.cancelled');
