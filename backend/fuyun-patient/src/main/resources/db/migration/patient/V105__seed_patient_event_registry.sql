-- V105：患者域事件契约种子登记（M02 Spec §7 MQ 发布事件清单 + M-25 成对语义，CF-3 冻结载体）。
-- 号段登记：patient 域 V100–V199（CHANGELOG 2026-09-16 条目），本文件为 V105；
--   种子放发布方模块 patient 目录但操作 integration.event_registry（V403 先例）。
-- 业务意图：先登记后发布（M20 治理红线）——患者事件发布器（Task 13）与任何订阅登记
--   （MessagingGovernance.declareConsumerQueue 启动期自动补订）都以本批种子行为前提。
-- 成对语义（M-25）：merged↔split、frozen↔unfrozen 成对登记，订阅方必须成对订阅——
--   成对关系由本模块在 payload_desc 中统一标注（V105 即「统一标注」载体）。
-- 敏感红线：payload_desc 与各事件载荷均禁完整敏感明文（02-patient 红线 3）。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V5/V403 先例）；id=9–16 接续 V5 的 1–7 + V403 的 8。

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 9, 'patient.created', 'patient',
       '患者建档：patientId/sex/birthDate/realNameFlag/registerChannel/archiveSource；含未实名标记场景；敏感字段禁入载荷（CF-3 冻结载体）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.created');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 10, 'patient.updated', 'patient',
       '患者主数据变更：patientId/changedFields(变更字段名清单)；敏感字段禁入载荷；P1 计划六事件外的补齐项（PUT 端点发布来源）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.updated');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 11, 'patient.merged', 'patient',
       '合并完成：survivorPatientId/mergedPatientId(指针映射)；成对语义(M-25)——凡订阅本事件的模块必须成对登记订阅 patient.split；订阅方幂等消费（eventId 去重）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.merged');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 12, 'patient.split', 'patient',
       '拆分恢复：restoredPatientId(从档恢复 NORMAL，标识按快照回挂)；patient.merged 的逆操作事件，与之一一成对(M-25)',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.split');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 13, 'patient.frozen', 'patient',
       '冻结：patientId/reason；冻结期间解析服务返回拦截标记（业务模块拒绝新就诊）；成对语义(M-25)——凡订阅本事件的模块必须成对登记订阅 patient.unfrozen',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.frozen');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 14, 'patient.unfrozen', 'patient',
       '解冻：patientId；patient.frozen 的逆操作事件，与之一一成对(M-25)；P1 计划六事件外的补齐项（M-25 成对裁决）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.unfrozen');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 15, 'patient.identifier.changed', 'patient',
       '标识变更：patientId/identifierType/valueHash/changeType(BOUND/LOST/REPLACED/UNBOUND)；解析缓存失效依据；禁带标识值明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.identifier.changed');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 16, 'patient.health-summary.updated', 'patient',
       '健康档案变更：patientId/hasAllergy/allergyCodes(过敏项 code 摘要)；M06 审方/M05 护理/M03 M04 开单场景过敏与禁忌提示依据',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'patient.health-summary.updated');
