-- V605：billing 事件契约种子登记（CF-4 冻结载体；V105/V5 先例）。
-- 三段名核验结论（计划 Global Constraints/范围声明）：六个 billing.* 事件本身即
--   <模块>.<实体>.<动作> 三段形态，QueueGovernorImpl EVENT_TYPE_PATTERN 全通过，零校正。
-- id 17–22 = CF-4 六类发布事件；id 23–24 = CF-5 上游事件占位登记（producer 归 outpatient/pharmacy，
--   载荷字段随 PR-4/PR-5 实装冻结——V5「占位 schema」先例；billing 启动期订阅自动回填
--   subscriber_modules=billing，seed 阶段留空）。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V5/V105 先例）。

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 17, 'billing.fee.created', 'billing',
       '费用生成（CF-4）：feeId/feeNo/patientId/visitId/chargeItemId/itemName/amount(分)/chargeSource/billingKey（字段名与 api FeeCreatedPayload record 组件逐字同源，2026-09-17 审查收口）；计价引擎落库后发布，M03/M04 费用回显依据；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.fee.created');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 18, 'billing.fee.confirmed', 'billing',
       '费用确认入账（CF-4）：feeId/patientId/visitId/amount；PENDING→CONFIRMED 迁移时发布（住院入账/门诊收费前确认）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.fee.confirmed');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 19, 'billing.settlement.completed', 'billing',
       '结算完成（CF-4）：settlementId/settleNo/patientId/visitId/settleType/payerType/totalAmount/pooledAmount/acctPayAmount/selfPayAmount（医保拆分摘要，金额均为分）；M03 订阅放行发药',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.settlement.completed');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 20, 'billing.refund.approved', 'billing',
       '退费审批通过（CF-4）：refundId/refundNo/settlementId/patientId/amount/refundType；免审直退同事件承载（autoApproved 字段区分，审计抽查）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.refund.approved');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 21, 'billing.deposit.changed', 'billing',
       '押金账户余额/预警变更（CF-4）：accountId/patientId/visitId/balance/status(NORMAL|ARREARS)；欠费提醒驱动（M01 通知触达随通知中心前置）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.deposit.changed');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 22, 'billing.charge-item-price.published', 'billing',
       '调价生效广播（CF-4）：chargeItemId/itemCode/priceVersion/price(分)/effectiveFrom；工作站价格缓存刷新（连字符实体段合规三段名）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.charge-item-price.published');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 23, 'outpatient.order.created', 'outpatient',
       'CF-5 占位（PR-3 billing 订阅先行）：orderId/visitId/patientId/lines[]{itemCode,quantity}；非药品计费行承载事件，正式字段随 M03 实装（PR-5）冻结——发布方迁移若冲突以本行契约双向评审为准',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.created');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 24, 'pharmacy.prescription.created', 'pharmacy',
       'CF-5 占位（PR-3 billing 订阅先行）：prescriptionId/visitId/patientId/lines[]{itemCode,quantity}；药品计费行处方生效事件（M-4 裁决口径），正式字段随 M06 实装（PR-4）冻结',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'pharmacy.prescription.created');
