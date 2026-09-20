-- V204：outpatient 事件契约种子（CF-3/CF-5 冻结载体；V605/V702 先例）。
-- 应用序要点（偏差①）：本迁移版本号低于 V605/V702——全新库先于 billing/pharmacy 种子执行，
--   故 id 23/25/31 采用「UPDATE（存量卷命中）+ WHERE NOT EXISTS 兜底 INSERT（新库落冻结行）」
--   双语句形态，两序同终态；V605/V702 的 INSERT ... WHERE NOT EXISTS 因行已在而自然跳过。
-- id 32–40 = 新登记（全局递增，裁决 1）；id 35 仅登记无发布点（id 27 先例，禁发布）；
--   id 39 为延迟队列回调内部事件（fy.delay 档位 appointment-timeout 到期经 DLX 以本路由键回 fy.topic）。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V5/V105/V605/V702 先例）。

-- ---------------------------------------------------------------- 1. 占位行冻结（id 23/25/31，CF-5 双向评审声明随 PR）
UPDATE integration.event_registry
SET payload_desc = '门诊申请单开立（CF-5 冻结，PR-5 实装，V605 占位升级）：orderId/visitId/patientId/lines[]{itemCode,quantity}（字段名与 api OrderCreatedPayload record 组件逐字同源；orderId=order_no 业务号，billing sourceRef 直取，quantity 为 DECIMAL string）；M13(本仓 billing) 生成 PENDING 费用（PR-3 已订阅，按 orderId 取 sourceRef）；禁敏感明文'
WHERE id = 23 AND event_type = 'outpatient.order.created';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 23, 'outpatient.order.created', 'outpatient',
       '门诊申请单开立（CF-5 冻结，PR-5 实装，V605 占位升级）：orderId/visitId/patientId/lines[]{itemCode,quantity}（字段名与 api OrderCreatedPayload record 组件逐字同源；orderId=order_no 业务号，billing sourceRef 直取，quantity 为 DECIMAL string）；M13(本仓 billing) 生成 PENDING 费用（PR-3 已订阅，按 orderId 取 sourceRef）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.created');

UPDATE integration.event_registry
SET payload_desc = '门诊缴费放行扇出（CF-5 冻结，PR-5 实装）：settlementId/settleNo/patientId/visitId/orderNos[]/rxNos[]/greenChannelFlag（与 api OrderChargedPayload record 组件逐字同源；orderNos/rxNos 为本次结算覆盖的申请单号与处方号精确清单）；M06(本仓 pharmacy) 处方转待调配（PR-5 起按 rxNos 单据精确放行，裁决 4）、M07/M08/M05 执行放行随 P3；禁敏感明文'
WHERE id = 25 AND event_type = 'outpatient.order.charged';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 25, 'outpatient.order.charged', 'outpatient',
       '门诊缴费放行扇出（CF-5 冻结，PR-5 实装）：settlementId/settleNo/patientId/visitId/orderNos[]/rxNos[]/greenChannelFlag（与 api OrderChargedPayload record 组件逐字同源；orderNos/rxNos 为本次结算覆盖的申请单号与处方号精确清单）；M06(本仓 pharmacy) 处方转待调配（PR-5 起按 rxNos 单据精确放行，裁决 4）、M07/M08/M05 执行放行随 P3；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.charged');

UPDATE integration.event_registry
SET payload_desc = '门诊退费逆向扇出（CF-5 冻结，PR-5 实装）：orderNo/patientId/visitId/rxNos[]/reason（与 api OrderCancelledPayload record 组件逐字同源；rxNos 为经 billing SettlementQueryPort 按 settlementId 反查的处方号清单）；本模块承担未发药作废与退药单终态确认（06-pharmacy §8 B-3 单向链）：M06 未发药处方作废/已退药单据收敛（PR-5 回切实装）、M07/M08/M05 随 P3；禁敏感明文'
WHERE id = 31 AND event_type = 'outpatient.order.cancelled';

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 31, 'outpatient.order.cancelled', 'outpatient',
       '门诊退费逆向扇出（CF-5 冻结，PR-5 实装）：orderNo/patientId/visitId/rxNos[]/reason（与 api OrderCancelledPayload record 组件逐字同源；rxNos 为经 billing SettlementQueryPort 按 settlementId 反查的处方号清单）；本模块承担未发药作废与退药单终态确认（06-pharmacy §8 B-3 单向链）：M06 未发药处方作废/已退药单据收敛（PR-5 回切实装）、M07/M08/M05 随 P3；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.order.cancelled');

-- ---------------------------------------------------------------- 2. 新登记（id 32–40）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 32, 'outpatient.visit.registered', 'outpatient',
       '门诊挂号/取号成功（PR-5 实装）：visitId/patientId/visitType/deptCode/doctorId（与 api VisitRegisteredPayload record 组件逐字同源；visitId=O+yyyyMMdd+5 位流水，M03 唯一签发，CF-3）；M13(本仓 billing) 医保就诊登记依据（订阅随 P3）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.registered');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 33, 'outpatient.visit.finished', 'outpatient',
       '门诊诊毕（PR-5 实装）：visitId/patientId/disposition/finishOperator（与 api VisitFinishedPayload record 组件逐字同源；disposition=离院去向国标代码 1~7/9，调研依据 1）；M09 信息页/病案与 M19 工作量统计取数依据（订阅随 P4）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.finished');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 34, 'outpatient.visit.cancelled', 'outpatient',
       '门诊退号回滚（PR-5 实装）：visitId/patientId/reason（与 api VisitCancelledPayload record 组件逐字同源）；M13 就诊登记撤销与 M18 患者端同步依据（订阅随 P3/P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.cancelled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 35, 'outpatient.visit.no-show', 'outpatient',
       '门诊爽约（仅登记，无发布点——发布点随当日爽约判定任务交付，id 27 先例，禁发布）：预期字段 visitId/patientId；号源释放与信用限约在 appointment 链承载（visit.no-show 为 visit 维度声明态）',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.visit.no-show');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 36, 'outpatient.appointment.booked', 'outpatient',
       '预约成功（PR-5 实装）：apptNo/patientId/schedDate/session/deptCode/apptType/channel（与 api AppointmentBookedPayload record 组件逐字同源；channel=窗口/自助机/公众号/小程序/诊间/外联，P1 实装窗口/portal 两渠道）；M18 患者端订单同步与 M19 统计依据（订阅随 P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.booked');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 37, 'outpatient.appointment.cancelled', 'outpatient',
       '退号完成（PR-5 实装）：apptNo/patientId/reason/feeRefundTriggered（与 api AppointmentCancelledPayload record 组件逐字同源；发布时点=号源已回池、退费联动已触发，feeRefundTriggered 区分支付时限内免退费路径）；M18 患者端订单同步依据（订阅随 P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.cancelled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 38, 'outpatient.appointment.rescheduled', 'outpatient',
       '改期完成（PR-5 实装）：oldApptNo/newApptNo/patientId/newSchedDate/newSlotStart（与 api AppointmentRescheduledPayload record 组件逐字同源；reschedule_of 链，号源先占新后退旧防两头空）；M18 患者端同步依据（订阅随 P2）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.rescheduled');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 39, 'outpatient.appointment.timeout', 'outpatient',
       '预约支付超时回调（PR-5 实装，自产自消内部事件）：apptNo/patientId/poolId（与 api AppointmentTimeoutPayload record 组件逐字同源；fy.delay 档位 appointment-timeout 到期经 DLX 以本路由键回 fy.topic，outpatient 自消费置 NO_SHOW+回池+信用记录；超时与支付成功并发以预约单状态 CAS 先到先得）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.appointment.timeout');

INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, subscriber_modules, status)
SELECT 40, 'outpatient.schedule.stopped', 'outpatient',
       '停诊广播（PR-5 实装）：scheduleId/schedDate/deptCode/doctorId/stopReason（与 api ScheduleStoppedPayload record 组件逐字同源；已约患者改期/退费联动依据，通知触达随 M01 通知中心）；M18/M19 消费随 P2（Spec §7-M03 流程 4）；禁敏感明文',
       '', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'outpatient.schedule.stopped');
