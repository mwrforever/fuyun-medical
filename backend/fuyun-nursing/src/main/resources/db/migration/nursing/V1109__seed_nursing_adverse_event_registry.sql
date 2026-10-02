-- V1109：M05 不良事件发布登记种子 id 83（FU-M05-09；P2 PR-3 Task 2；台账已先记再改）。
-- id 排定：83（全局递增，接 V1102 ward id 82）；status 一律 ACTIVE（词表 ACTIVE/DEPRECATED，V2:13）。
-- 三方一致红线（GC4）：本文件 desc ↔ NursingMessagingConstants.EVENT_ADVERSE_EVENT_REPORTED 字面量 ↔
--   nursing/api AdverseEventReportedPayload 组件名逐字同源（契约锚 NursingEventContractTest），单向漂移即红灯。
-- 幂等形态：INSERT...SELECT 存在性守卫（V1102/V1004 先例）；禁敏感明文（不含患者姓名/证件等敏感字段）。

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 83, 'nursing.adverse-event.reported', 'nursing',
       '护理不良事件上报：载荷五字段 eventNo/category/severityClass/wardId/occurredAt；匿名上报不含 reporter；M19 护理质量指标消费（缺位登记）；发布面 FU-M05-09 归 P2 PR-3 实装',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE id = 83);
