-- V1004：CF-7 实装事件族种子登记 id 74–81 八行（P2 PR-2 Task 2；iot 增量走通用段 V1004+，2026-09-26 勘误先记再改，台账 docs/migrations/flyway-version-registry.md 已登记）。
-- 三方一致红线：本文件 desc ↔ IotMessagingConstants 事件字面量 ↔ iot/api/payload 载荷 record 组件名逐字同源（契约锚 IotMessagingContractTest），单向漂移即红灯；幂等形态 INSERT...SELECT 存在性守卫（V607/V901 先例）。

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 74, 'iot.alarm.triggered', 'iot',
       '告警触发：alarmNo/deviceId/patientId/visitId/wardId/alarmLevel(INFO|WARNING|CRITICAL)/metricCode/triggerValue/ruleId/occurredAt；M05 挂单升级与 M16 播报消费',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.alarm.triggered' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 75, 'iot.alarm.escalated', 'iot',
       '告警升级动作：alarmNo/deviceId/wardId/escalationLevel/escalatedAt；升级为动作非状态',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.alarm.escalated' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 76, 'iot.alarm.closed', 'iot',
       '告警关闭：alarmNo/deviceId/wardId/closedBy/closedAt/closeReason；M05/M16 复位与统计',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.alarm.closed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 77, 'iot.binding.changed', 'iot',
       '绑定变更：deviceId/patientId/visitId/bedId/wardId/bindType/changeType(BIND|UNBIND)/occurredAt；绑定五元组变更广播',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.binding.changed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 78, 'iot.telemetry.anomaly', 'iot',
       '遥测断流异常：deviceId/metricCode/anomalyType/lastOccurredAt/detectedAt；M16 体征质量确认提示',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.telemetry.anomaly' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 79, 'iot.command.completed', 'iot',
       '命令结果回推：commandNo/deviceId/commandName/status/operator/completedAt/errorMsg',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.command.completed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 80, 'iot.linkage.executed', 'iot',
       '联动执行：linkageNo/ruleId/triggerSource/triggerRef/actionType/actionResult/executedAt',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.linkage.executed' AND deleted = 0);

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 81, 'iot.call.triggered', 'iot',
       '设备呼叫触发：callNo/deviceId/callType/bedId/wardId/triggeredAt；M16 呼叫域入口',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'iot.call.triggered' AND deleted = 0);
