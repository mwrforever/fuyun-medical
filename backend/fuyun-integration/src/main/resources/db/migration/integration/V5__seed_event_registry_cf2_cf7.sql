-- V5：事件契约台账首批种子（BRIEF-PR2-01 §4.3，PR-2 DoD 第 4 条"迁移+测试断言"落点）
--   id 1    CF-1 事件信封约定冻结行（integration.convention.event-envelope，零订阅广播）
--   id 2-6  CF-2 五个 system.* 主数据事件（M01 Spec §7 MQ 事件发布清单全部明示，无推导项；订阅方待登记）
--   id 7    CF-7 标准遥测消息模型冻结行（iot.telemetry.message，iot 自登记）
-- 登记形态说明：CF-1/CF-7 为非 <模块>.<实体>.<动作> 的契约载体行，Spec 未定义其登记形态，
--   按 BRIEF-PR2-01 §4.3/§8-2 推导定案落盘；若终验（P6 DoD 第 4 条核对）口径不同，
--   仅需调整种子数据（一行 SQL），不动表结构。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（版化迁移只跑一次，防人工重放重复插入）。
-- 审计口径：registered_at/created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by
--   取默认 'system'（backend 宪法 A.4.2-9），updated_at 另由 V1 公共触发器函数统一维护。

-- id 1：CF-1 事件信封约定冻结行（全系统事件发布/消费的唯一契约形态）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 1,
       'integration.convention.event-envelope',
       'integration',
       'CF-1 事件信封约定冻结：eventId/occurredAt/producer/eventType/payloadVersion/traceId/payload 七字段必填规则 + fy.topic/fy.dlx/fy.delay 三交换机 + q.<消费者>.<事件> 队列命名',
       'broadcast',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'integration.convention.event-envelope');

-- id 2：CF-2 字典发布广播（载荷占位 schema，正式字段随 PR-3 M01 实装冻结）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 2,
       'system.dict.published',
       'system',
       'CF-2 字典发布广播占位载荷：dictType=字典类型编码(type_code)，version=字典版本号；占位 schema，正式字段随 PR-3 M01 实装冻结',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'system.dict.published');

-- id 3：CF-2 机构变更广播（变更类事件，载荷同构推导）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 3,
       'system.org.changed',
       'system',
       'CF-2 机构变更占位载荷：orgId=机构ID(雪花)，changeType=变更类型；占位 schema，正式字段随 PR-3 M01 实装冻结',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'system.org.changed');

-- id 4：CF-2 用户变更广播（变更类事件，载荷同构推导）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 4,
       'system.user.changed',
       'system',
       'CF-2 用户变更占位载荷：userId=用户ID(雪花)，changeType=变更类型；占位 schema，正式字段随 PR-3 M01 实装冻结',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'system.user.changed');

-- id 5：CF-2 参数变更广播（module 字段为 FU-M01-07 Spec 明示）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 5,
       'system.param.changed',
       'system',
       'CF-2 参数变更占位载荷：module=参数模块(FU-M01-07 明示)，paramKey=参数键；占位 schema，正式字段随 PR-3 M01 实装冻结',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'system.param.changed');

-- id 6：CF-2 执业授权变更广播（grantType 为 M01 §5 practice_grant Spec 字段）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 6,
       'system.practice.changed',
       'system',
       'CF-2 执业授权变更占位载荷：employeeId=员工ID(雪花)，grantType=授权类型(Spec 字段)，status=变更后授权状态；占位 schema，正式字段随 PR-3 M01 实装冻结',
       '',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'system.practice.changed');

-- id 7：CF-7 标准遥测消息模型冻结行（P0 只登记不消费，SPI 与消费链路随 PR-4 交付）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 7,
       'iot.telemetry.message',
       'iot',
       'CF-7 标准遥测消息模型冻结：deviceId/metricCode/value/unit/occurredAt/quality/source 七字段四路同构(FU-M14-05)，P0 仅登记，SPI 与消费随 PR-4',
       'iot',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.telemetry.message');
