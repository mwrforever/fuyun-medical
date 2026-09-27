-- V1102：ward 域事件登记种子 id 82（P2 PR-2 Task 12；ward 段首批收官行）。
-- 三方一致红线：本文件 desc ↔ WardMessagingConstants 事件字面量 ↔ ward/api 载荷 record
-- ColdChainAlertArchivedPayload 组件名逐字同源（契约锚 WardMessagingContractTest），单向漂移即红灯；
-- 幂等形态 INSERT...SELECT 存在性守卫（V1004/V607/V901 先例）。

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 82, 'ward.cold-chain.alert-archived', 'ward',
       '冷链告警处置归档：archiveNo/recordNo/alarmRef/purpose(VACCINE|BLOOD|REAGENT|PHARMA)/handledBy/handledAt；M05 督办联动与冷链台账闭环消费',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'ward.cold-chain.alert-archived' AND deleted = 0);
