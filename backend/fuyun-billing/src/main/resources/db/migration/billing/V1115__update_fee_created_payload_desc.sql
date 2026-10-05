-- V1115：billing.fee.created 载荷契约 UPDATE（W-67b 净解；台账已先记再改，PR-4E 立项占号）。
-- 契约演进双向评审声明（CF-5）：本 UPDATE 仅向 id 17（billing.fee.created）payload_desc 尾部
--   追加 visitType 组件声明——只增不删，旧文本逐字保留；既有消费方 outpatient（申请单推进，
--   本迁移同 PR 挂 visitType=IN 跳过分流）与 pharmacy（收费链同步，住院行走处方通道守卫不消费
--   该组件）按原子集取用不受影响；新增组件经 Jackson 按名取值，旧版本事件帧缺省该组件时
--   消费端 asText("") 兜底走原路径，反序列化零影响。
-- 守卫口径：id + event_type 双条件（V1111 先例）再叠加 payload_desc NOT LIKE 幂等守卫
--   （防重跑重复追加，比 V1111 更稳的合法增强）；本迁移为纯 UPDATE 无 INSERT 守卫面。
-- api 侧组件名锚点：FeeCreatedPayload record 十组件（V605 原九组件 + 本迁移尾部追加 visitType，
--   契约测试 BillingPayloadsContractTest 组件序反射断言同源锚定）。

UPDATE integration.event_registry
SET payload_desc = payload_desc || '；visitType=就诊类型（OUT=门诊/IN=住院/PEIS=体检预留，发布方取计费命令 visitType 恒填——消费方按其分流住院行，W-67b）'
WHERE id = 17 AND event_type = 'billing.fee.created' AND payload_desc NOT LIKE '%visitType=%';
