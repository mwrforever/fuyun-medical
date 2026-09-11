-- V403：IoT 设备状态变更事件种子登记（M14 Spec §7 MQ 事件清单，BRIEF-PR4-01 §2）。
-- 号段登记（BRIEF-PR4-01 §1.1）：iot 域 V400–V499，本文件为 V403。
-- 业务意图：P0 唯一 iot 发布事件先登记后订阅（M20 治理链路红线）——消费队列
--   q.iot.iot.device.status-changed 经 MessagingGovernance 构件声明时自动补登记订阅方，
--   本种子保证事件先于任何订阅登记存在。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V5/V303 先例，版化迁移只跑一次，防人工重放重复插入）。
-- ID 取值说明：沿用 V5 种子小整数连续编号（雪花 ID 为 19 位量级永不冲突，V303 同口径），id=8 接续 V5 的 1–7。
-- 审计口径：registered_at/created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by
--   取默认 'system'（宪法 A.4.2-9），updated_at 另由 V1 公共触发器函数统一维护。

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 8,
       'iot.device.status-changed',
       'iot',
       '设备状态变更（P0 占位载荷：deviceId/status/occurredAt/wardId，正式契约随 P1 设备状态管理冻结）',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.device.status-changed');
