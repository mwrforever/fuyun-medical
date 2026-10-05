-- V1117：业务角色六席位 + 全量角色权限绑定 + 演示账号族种子（P2 PR-4D Task 3 / 计划 2026-10-03-p2-pr4d-rbac-full 裁定 D6/D7）。
-- 装配口径：6 业务角色（DOCTOR/NURSE/PHARMACIST/CASHIER/REGISTRAR/IOT_ADMIN，ADMIN 运行期全放不种绑定=裁定 D3）
--   + 角色权限绑定 API 段 315 行（附件 A 每挂码×授予角色列展开，ADMIN 专属空白列不生成行）
--   + MENU 段 42 行（附件 B 32 码×授予角色列展开，billing:refund:approve 为 ADMIN 专属不生成行）
--   + doctordemo 增绑 DOCTOR 1 行 + 演示账号族 5 账号×3 行（sys_user/sys_employee/sys_user_role）。
-- 幂等形态：全段 INSERT ... SELECT ... WHERE NOT EXISTS（V303/V1116 先例）——绑定段经 VALUES 映射表 join
--   双表按 role_code/perm_code 解析 id + deleted=0 精确匹配，重放零重复零唯一冲突；
--   绑定 id 用「段前缀 + row_number() OVER ()」生成（窗口函数在 NOT EXISTS 过滤后编号，首跑全量连续、
--   重放零行插入不消耗 id）。
-- ID 取值：角色行 101~106（小整数避开 V303 的 id=1，跨表 ID 空间独立）；API 绑定 1117000000000000000+序、
--   MENU 绑定 1117001000000000000+序（sys_role_permission 表内分段防撞）；doctordemo 增绑 1117001000000000001
--   与演示账号 user_role 1117002000000000001~0005（sys_user_role 表独立主键空间，与 sys_role_permission
--   同号跨表不冲突——V303 六表同用 id 1~6 先例）。
-- 演示账号口令与 V303:70-72 红线注记同源：Fuyun@2026 仅存 bcrypt 哈希，禁明文入库。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认 'system'
--   （backend 宪法 A.4.2-9）；updated_at 另由 V300 各表触发器统一维护。

-- ---------------------------------------------------------------- 1. 业务角色六席位（数据范围按域语义：临床域 WARD / 收费登记域 HOSP / 物联网运维域 ALL）
INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 101, 'DOCTOR', '医师', 'WARD', 'ACTIVE', 'P2 业务角色：医嘱/处方/接诊等临床域权限（V1117 附件 A/B 矩阵展开）'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'DOCTOR' AND deleted = 0);

INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 102, 'NURSE', '护士', 'WARD', 'ACTIVE', 'P2 业务角色：护理执行/病区管理/分诊等护理域权限（V1117 附件 A/B 矩阵展开）'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'NURSE' AND deleted = 0);

INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 103, 'PHARMACIST', '药师', 'WARD', 'ACTIVE', 'P2 业务角色：摆药/审方/药品维护等药学域权限（V1117 附件 A/B 矩阵展开）'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'PHARMACIST' AND deleted = 0);

INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 104, 'CASHIER', '收费员', 'HOSP', 'ACTIVE', 'P2 业务角色：结算收费/退费执行/押金等收费域权限（V1117 附件 A/B 矩阵展开）'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'CASHIER' AND deleted = 0);

INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 105, 'REGISTRAR', '挂号员', 'HOSP', 'ACTIVE', 'P2 业务角色：预约挂号/建档/入院登记等登记域权限（V1117 附件 A/B 矩阵展开）'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'REGISTRAR' AND deleted = 0);

INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 106, 'IOT_ADMIN', '物联网管理员', 'ALL', 'ACTIVE', 'P2 业务角色：设备/绑定/告警规则/冷链等物联网运维域权限（V1117 附件 A/B 矩阵展开）'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'IOT_ADMIN' AND deleted = 0);

-- ---------------------------------------------------------------- 2. 角色权限绑定·API 段（附件 A「授予角色」列展开，315 行）
-- perm_code 与 V1116 实际 INSERT 的码逐字一致（join 解析 id，禁手写数字 id）；
-- 附件 A 授予角色为空的挂码（管理面/资金审批面/规则维护面/集成面=ADMIN 专属）不生成绑定行。
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1117000000000000000 + row_number() OVER (),
       r.id, p.id
FROM (VALUES
    -- A.1 system 域（7 行：字典读共享面 6 角色 + 执业校验 DOCTOR）
    ('DOCTOR', 'GET /api/v1/system/dicts/{typeCode}'),
    ('NURSE', 'GET /api/v1/system/dicts/{typeCode}'),
    ('PHARMACIST', 'GET /api/v1/system/dicts/{typeCode}'),
    ('CASHIER', 'GET /api/v1/system/dicts/{typeCode}'),
    ('REGISTRAR', 'GET /api/v1/system/dicts/{typeCode}'),
    ('IOT_ADMIN', 'GET /api/v1/system/dicts/{typeCode}'),
    ('DOCTOR', 'POST /api/v1/system/practice/check'),
    -- A.2 patient 域（60 行）
    ('CASHIER', 'GET /api/v1/patient/card-accounts/{id}'),
    ('REGISTRAR', 'GET /api/v1/patient/card-accounts/{id}'),
    ('CASHIER', 'POST /api/v1/patient/card-accounts/{id}/freeze'),
    ('REGISTRAR', 'POST /api/v1/patient/card-accounts/{id}/freeze'),
    ('CASHIER', 'POST /api/v1/patient/card-accounts/{id}/close'),
    ('REGISTRAR', 'POST /api/v1/patient/card-accounts/{id}/close'),
    ('CASHIER', 'GET /api/v1/patient/card-accounts/{id}/txns'),
    ('REGISTRAR', 'GET /api/v1/patient/card-accounts/{id}/txns'),
    ('REGISTRAR', 'POST /api/v1/patient/cards/issue'),
    ('CASHIER', 'POST /api/v1/patient/cards/issue'),
    ('REGISTRAR', 'POST /api/v1/patient/cards/bind'),
    ('CASHIER', 'POST /api/v1/patient/cards/bind'),
    ('REGISTRAR', 'POST /api/v1/patient/cards/loss/{cardNo}'),
    ('CASHIER', 'POST /api/v1/patient/cards/loss/{cardNo}'),
    ('REGISTRAR', 'POST /api/v1/patient/cards/replace'),
    ('CASHIER', 'POST /api/v1/patient/cards/replace'),
    ('REGISTRAR', 'POST /api/v1/patient/cards/unbind/{cardNo}'),
    ('CASHIER', 'POST /api/v1/patient/cards/unbind/{cardNo}'),
    ('REGISTRAR', 'GET /api/v1/patient/cards/{cardNo}'),
    ('CASHIER', 'GET /api/v1/patient/cards/{cardNo}'),
    ('DOCTOR', 'GET /api/v1/patient/patients/{patientId}/health-summary'),
    ('NURSE', 'GET /api/v1/patient/patients/{patientId}/health-summary'),
    ('DOCTOR', 'POST /api/v1/patient/patients/{patientId}/health-items'),
    ('NURSE', 'POST /api/v1/patient/patients/{patientId}/health-items'),
    ('DOCTOR', 'POST /api/v1/patient/health-items/{id}/correct'),
    ('NURSE', 'POST /api/v1/patient/health-items/{id}/correct'),
    ('REGISTRAR', 'POST /api/v1/patient/patients'),
    ('DOCTOR', 'POST /api/v1/patient/patients'),
    ('NURSE', 'POST /api/v1/patient/patients'),
    ('REGISTRAR', 'POST /api/v1/patient/patients/match-check'),
    ('DOCTOR', 'POST /api/v1/patient/patients/match-check'),
    ('NURSE', 'POST /api/v1/patient/patients/match-check'),
    ('REGISTRAR', 'GET /api/v1/patient/patients/{patientId}'),
    ('DOCTOR', 'GET /api/v1/patient/patients/{patientId}'),
    ('NURSE', 'GET /api/v1/patient/patients/{patientId}'),
    ('PHARMACIST', 'GET /api/v1/patient/patients/{patientId}'),
    ('REGISTRAR', 'PUT /api/v1/patient/patients/{patientId}'),
    ('DOCTOR', 'PUT /api/v1/patient/patients/{patientId}'),
    ('REGISTRAR', 'GET /api/v1/patient/patients/search'),
    ('DOCTOR', 'GET /api/v1/patient/patients/search'),
    ('NURSE', 'GET /api/v1/patient/patients/search'),
    ('PHARMACIST', 'GET /api/v1/patient/patients/search'),
    ('REGISTRAR', 'POST /api/v1/patient/patients/{patientId}/freeze'),
    ('REGISTRAR', 'POST /api/v1/patient/patients/{patientId}/unfreeze'),
    ('REGISTRAR', 'POST /api/v1/patient/identifiers/resolve'),
    ('DOCTOR', 'POST /api/v1/patient/identifiers/resolve'),
    ('NURSE', 'POST /api/v1/patient/identifiers/resolve'),
    ('PHARMACIST', 'POST /api/v1/patient/identifiers/resolve'),
    ('REGISTRAR', 'POST /api/v1/patient/patients/{patientId}/identifiers'),
    ('DOCTOR', 'POST /api/v1/patient/patients/{patientId}/identifiers'),
    ('NURSE', 'POST /api/v1/patient/patients/{patientId}/identifiers'),
    ('REGISTRAR', 'GET /api/v1/patient/patients/{patientId}/identifiers'),
    ('DOCTOR', 'GET /api/v1/patient/patients/{patientId}/identifiers'),
    ('NURSE', 'GET /api/v1/patient/patients/{patientId}/identifiers'),
    ('PHARMACIST', 'GET /api/v1/patient/patients/{patientId}/identifiers'),
    ('REGISTRAR', 'GET /api/v1/patient/privacy-auths'),
    ('DOCTOR', 'GET /api/v1/patient/privacy-auths'),
    ('REGISTRAR', 'POST /api/v1/patient/privacy-auths'),
    ('DOCTOR', 'POST /api/v1/patient/privacy-auths'),
    ('DOCTOR', 'POST /api/v1/patient/privacy/unmask'),
    -- A.3 billing 域（18 行：审批/调价/医保面 ADMIN 专属不展开）
    ('DOCTOR', 'GET /api/v1/billing/charge-items/by-code/{itemCode}'),
    ('CASHIER', 'GET /api/v1/billing/charge-items/by-code/{itemCode}'),
    ('CASHIER', 'GET /api/v1/billing/daily-lists'),
    ('CASHIER', 'POST /api/v1/billing/deposits'),
    ('CASHIER', 'GET /api/v1/billing/deposits'),
    ('DOCTOR', 'POST /api/v1/billing/pricing/quote'),
    ('CASHIER', 'POST /api/v1/billing/pricing/quote'),
    ('DOCTOR', 'POST /api/v1/billing/fees/manual'),
    ('CASHIER', 'POST /api/v1/billing/fees/manual'),
    ('DOCTOR', 'GET /api/v1/billing/fees'),
    ('CASHIER', 'GET /api/v1/billing/fees'),
    ('CASHIER', 'POST /api/v1/billing/fees/{id}/cancel'),
    ('CASHIER', 'POST /api/v1/billing/refunds'),
    ('CASHIER', 'POST /api/v1/billing/refunds/{id}/execute'),
    ('CASHIER', 'GET /api/v1/billing/refunds'),
    ('CASHIER', 'POST /api/v1/billing/settlements/preview'),
    ('CASHIER', 'POST /api/v1/billing/settlements'),
    ('CASHIER', 'GET /api/v1/billing/settlements/{no}'),
    -- A.4 pharmacy 域（28 行：药品医保对照 ADMIN 专属不展开）
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispenses/{no}/pick'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispenses/{no}/verify'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispenses/{no}/issue'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-returns'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/medication-occupancy'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/dispenses'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-plans/generate'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-plans/{no}/pick'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-plans/{no}/verify'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-plans/{no}/issue'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-plans/{no}/deliver'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/dispense-plans/{no}/receive'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/dispense-plans'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/dispense-plans/{no}/label'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/dispense-plans/{no}/returnable'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/drugs'),
    ('PHARMACIST', 'PUT /api/v1/pharmacy/drugs/{id}'),
    ('DOCTOR', 'GET /api/v1/pharmacy/drugs/{id}'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/drugs/{id}'),
    ('DOCTOR', 'GET /api/v1/pharmacy/drugs/search'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/drugs/search'),
    ('DOCTOR', 'POST /api/v1/pharmacy/prescriptions'),
    ('DOCTOR', 'POST /api/v1/pharmacy/prescriptions/{no}/cancel'),
    ('DOCTOR', 'GET /api/v1/pharmacy/prescriptions'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/prescriptions'),
    ('PHARMACIST', 'GET /api/v1/pharmacy/review-tasks'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/review-tasks/{id}/approve'),
    ('PHARMACIST', 'POST /api/v1/pharmacy/review-tasks/{id}/reject'),
    -- A.5 outpatient 域（21 行：排班维护/号源调度面 ADMIN 专属不展开）
    ('REGISTRAR', 'POST /api/v1/outpatient/appointments'),
    ('REGISTRAR', 'POST /api/v1/outpatient/appointments/{no}/take'),
    ('REGISTRAR', 'POST /api/v1/outpatient/appointments/{no}/cancel'),
    ('REGISTRAR', 'POST /api/v1/outpatient/appointments/{no}/reschedule'),
    ('REGISTRAR', 'GET /api/v1/outpatient/appt-credits'),
    ('REGISTRAR', 'POST /api/v1/outpatient/appt-credits/{id}/release'),
    ('DOCTOR', 'POST /api/v1/outpatient/visits/{visitId}/prescriptions'),
    ('DOCTOR', 'POST /api/v1/outpatient/orders/{no}/cancel'),
    ('DOCTOR', 'GET /api/v1/outpatient/orders'),
    ('REGISTRAR', 'POST /api/v1/outpatient/queue/call'),
    ('REGISTRAR', 'POST /api/v1/outpatient/queue/tickets/{id}/pass'),
    ('REGISTRAR', 'POST /api/v1/outpatient/queue/tickets/{id}/recall'),
    ('REGISTRAR', 'GET /api/v1/outpatient/schedule-templates'),
    ('REGISTRAR', 'GET /api/v1/outpatient/schedules'),
    ('REGISTRAR', 'GET /api/v1/outpatient/number-pools/available'),
    ('NURSE', 'POST /api/v1/outpatient/triage/check-in'),
    ('NURSE', 'POST /api/v1/outpatient/triage/adjust'),
    ('DOCTOR', 'POST /api/v1/outpatient/visits/{visitId}/admit'),
    ('DOCTOR', 'POST /api/v1/outpatient/visits/{visitId}/finish'),
    ('DOCTOR', 'GET /api/v1/outpatient/doctor/patient-queue'),
    ('DOCTOR', 'POST /api/v1/outpatient/visits/{visitId}/orders'),
    -- A.6 inpatient 域（55 行）
    ('DOCTOR', 'POST /api/v1/inpatient/admissions'),
    ('REGISTRAR', 'GET /api/v1/inpatient/admissions'),
    ('DOCTOR', 'GET /api/v1/inpatient/admissions'),
    ('NURSE', 'GET /api/v1/inpatient/admissions'),
    ('DOCTOR', 'POST /api/v1/inpatient/admissions/{no}/schedule'),
    ('DOCTOR', 'POST /api/v1/inpatient/admissions/{no}/cancel'),
    ('REGISTRAR', 'POST /api/v1/inpatient/admissions/{no}/register'),
    ('REGISTRAR', 'POST /api/v1/inpatient/visits/{visitId}/admit-ward'),
    ('NURSE', 'POST /api/v1/inpatient/visits/{visitId}/admit-ward'),
    ('REGISTRAR', 'GET /api/v1/inpatient/visits/arrears'),
    ('CASHIER', 'GET /api/v1/inpatient/visits/arrears'),
    ('REGISTRAR', 'GET /api/v1/inpatient/beds/map'),
    ('DOCTOR', 'GET /api/v1/inpatient/beds/map'),
    ('NURSE', 'GET /api/v1/inpatient/beds/map'),
    ('REGISTRAR', 'POST /api/v1/inpatient/beds/{id}/reserve'),
    ('NURSE', 'POST /api/v1/inpatient/beds/{id}/reserve'),
    ('REGISTRAR', 'POST /api/v1/inpatient/beds/{id}/assign'),
    ('NURSE', 'POST /api/v1/inpatient/beds/{id}/assign'),
    ('REGISTRAR', 'POST /api/v1/inpatient/beds/{id}/release'),
    ('NURSE', 'POST /api/v1/inpatient/beds/{id}/release'),
    ('NURSE', 'POST /api/v1/inpatient/beds/{id}/disinfect-done'),
    ('NURSE', 'POST /api/v1/inpatient/beds/{id}/maintain'),
    ('NURSE', 'POST /api/v1/inpatient/beds/{id}/maintain-done'),
    ('DOCTOR', 'POST /api/v1/inpatient/consultations'),
    ('DOCTOR', 'POST /api/v1/inpatient/consultations/{no}/accept'),
    ('DOCTOR', 'POST /api/v1/inpatient/consultations/{no}/opinion'),
    ('DOCTOR', 'POST /api/v1/inpatient/consultations/{no}/cancel'),
    ('DOCTOR', 'GET /api/v1/inpatient/consultations'),
    ('DOCTOR', 'POST /api/v1/inpatient/visits/{visitId}/discharge-request'),
    ('DOCTOR', 'POST /api/v1/inpatient/discharge-requests/{no}/cancel'),
    ('DOCTOR', 'GET /api/v1/inpatient/discharge-requests/{no}/clearance'),
    ('CASHIER', 'GET /api/v1/inpatient/discharge-requests/{no}/clearance'),
    ('DOCTOR', 'POST /api/v1/inpatient/discharge-requests/{no}/confirm'),
    ('DOCTOR', 'POST /api/v1/inpatient/visits/{visitId}/orders'),
    ('DOCTOR', 'GET /api/v1/inpatient/orders'),
    ('DOCTOR', 'GET /api/v1/inpatient/orders/{no}'),
    ('NURSE', 'GET /api/v1/inpatient/orders/{no}'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/{no}/stop'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/{no}/cancel'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/{no}/revoke-audit'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/reorganize'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/{no}/resubmit'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/{no}/oral-confirm'),
    ('NURSE', 'GET /api/v1/inpatient/order-plans'),
    ('NURSE', 'POST /api/v1/inpatient/order-plans/standby-trigger'),
    ('NURSE', 'POST /api/v1/inpatient/order-plans/{no}/execute-confirm'),
    ('DOCTOR', 'GET /api/v1/inpatient/orders/{no}/trace'),
    ('NURSE', 'GET /api/v1/inpatient/orders/{no}/trace'),
    ('DOCTOR', 'GET /api/v1/inpatient/transfer-worklist'),
    ('NURSE', 'GET /api/v1/inpatient/transfer-worklist'),
    ('DOCTOR', 'POST /api/v1/inpatient/orders/transfer-check'),
    ('REGISTRAR', 'POST /api/v1/inpatient/visits/{visitId}/transfer'),
    ('NURSE', 'POST /api/v1/inpatient/visits/{visitId}/transfer'),
    ('REGISTRAR', 'POST /api/v1/inpatient/visits/{visitId}/change-bed'),
    ('NURSE', 'POST /api/v1/inpatient/visits/{visitId}/change-bed'),
    -- A.7 nursing 域（54 行：50 挂码 NURSE 独占 + 病区患者读面双角色）
    ('NURSE', 'POST /api/v1/nursing/adverse-events'),
    ('NURSE', 'GET /api/v1/nursing/adverse-events'),
    ('NURSE', 'POST /api/v1/nursing/adverse-events/{no}/handle'),
    ('NURSE', 'POST /api/v1/nursing/adverse-events/{no}/close'),
    ('NURSE', 'POST /api/v1/nursing/adverse-events/{no}/return'),
    ('NURSE', 'GET /api/v1/nursing/stats/adverse-events'),
    ('NURSE', 'POST /api/v1/nursing/executions/{no}/needle-out'),
    ('NURSE', 'GET /api/v1/nursing/infusions/active'),
    ('NURSE', 'POST /api/v1/nursing/io-records'),
    ('NURSE', 'GET /api/v1/nursing/io-records'),
    ('NURSE', 'POST /api/v1/nursing/io-summaries'),
    ('NURSE', 'GET /api/v1/nursing/io-summaries'),
    ('NURSE', 'GET /api/v1/nursing/assessment-scales'),
    ('NURSE', 'POST /api/v1/nursing/assessments'),
    ('NURSE', 'GET /api/v1/nursing/assessments'),
    ('NURSE', 'POST /api/v1/nursing/nursing-records'),
    ('NURSE', 'GET /api/v1/nursing/nursing-records'),
    ('NURSE', 'GET /api/v1/nursing/nursing-records/{recordNo}'),
    ('NURSE', 'POST /api/v1/nursing/nursing-records/{recordNo}/submit'),
    ('NURSE', 'POST /api/v1/nursing/nursing-records/{recordNo}/revise'),
    ('NURSE', 'POST /api/v1/nursing/tasks'),
    ('NURSE', 'GET /api/v1/nursing/tasks'),
    ('NURSE', 'POST /api/v1/nursing/tasks/{taskNo}/complete'),
    ('NURSE', 'POST /api/v1/nursing/tasks/{taskNo}/claim'),
    ('NURSE', 'POST /api/v1/nursing/tasks/generate-routine'),
    ('NURSE', 'POST /api/v1/nursing/tasks/{taskNo}/cancel'),
    ('NURSE', 'GET /api/v1/nursing/executions'),
    ('NURSE', 'POST /api/v1/nursing/executions/{no}/sign-receive'),
    ('NURSE', 'POST /api/v1/nursing/executions/{no}/check'),
    ('NURSE', 'POST /api/v1/nursing/executions/{no}/start'),
    ('NURSE', 'POST /api/v1/nursing/executions/{no}/finish'),
    ('NURSE', 'POST /api/v1/nursing/executions/{no}/cancel'),
    ('NURSE', 'GET /api/v1/nursing/executions/occupancy'),
    ('NURSE', 'GET /api/v1/nursing/executions/{no}/trace'),
    ('NURSE', 'POST /api/v1/nursing/pda/override-check'),
    ('NURSE', 'GET /api/v1/nursing/pda/patient-summary'),
    ('NURSE', 'POST /api/v1/nursing/pda/patrol'),
    ('NURSE', 'POST /api/v1/nursing/handovers/generate'),
    ('NURSE', 'POST /api/v1/nursing/handovers/{handoverNo}/complete'),
    ('NURSE', 'GET /api/v1/nursing/handovers'),
    ('NURSE', 'GET /api/v1/nursing/temperature-charts'),
    ('NURSE', 'POST /api/v1/nursing/temperature-charts/{visitId}/special-events'),
    ('NURSE', 'POST /api/v1/nursing/vital-signs'),
    ('NURSE', 'GET /api/v1/nursing/vital-signs'),
    ('NURSE', 'GET /api/v1/nursing/vital-signs/pending-review'),
    ('NURSE', 'POST /api/v1/nursing/vital-signs/{id}/confirm'),
    ('NURSE', 'POST /api/v1/nursing/vital-signs/{id}/reject'),
    ('NURSE', 'GET /api/v1/nursing/ward-patients'),
    ('DOCTOR', 'GET /api/v1/nursing/ward-patients'),
    ('NURSE', 'GET /api/v1/nursing/ward-patients/{visitId}'),
    ('DOCTOR', 'GET /api/v1/nursing/ward-patients/{visitId}'),
    ('NURSE', 'GET /api/v1/nursing/assignments'),
    ('NURSE', 'POST /api/v1/nursing/assignments'),
    ('NURSE', 'DELETE /api/v1/nursing/assignments/{id}'),
    -- A.8 iot 域（52 行：全部 IOT_ADMIN）
    ('IOT_ADMIN', 'POST /api/v1/iot/alarms/{alarmNo}/acknowledge'),
    ('IOT_ADMIN', 'POST /api/v1/iot/alarms/{alarmNo}/close'),
    ('IOT_ADMIN', 'GET /api/v1/iot/alarm-rules'),
    ('IOT_ADMIN', 'POST /api/v1/iot/alarm-rules'),
    ('IOT_ADMIN', 'PUT /api/v1/iot/alarm-rules/{id}'),
    ('IOT_ADMIN', 'DELETE /api/v1/iot/alarm-rules/{id}'),
    ('IOT_ADMIN', 'POST /api/v1/iot/alarm-rules/{id}/simulate'),
    ('IOT_ADMIN', 'POST /api/v1/iot/bindings'),
    ('IOT_ADMIN', 'POST /api/v1/iot/bindings/{deviceId}/unbind'),
    ('IOT_ADMIN', 'GET /api/v1/iot/bindings'),
    ('IOT_ADMIN', 'GET /api/v1/iot/bindings/wards/{wardId}'),
    ('IOT_ADMIN', 'GET /api/v1/iot/bindings/devices/{deviceId}/active'),
    ('IOT_ADMIN', 'POST /api/v1/iot/commands/confirm-challenge'),
    ('IOT_ADMIN', 'POST /api/v1/iot/commands'),
    ('IOT_ADMIN', 'GET /api/v1/iot/commands'),
    ('IOT_ADMIN', 'GET /api/v1/iot/commands/{commandNo}'),
    ('IOT_ADMIN', 'GET /api/v1/iot/consume-errors'),
    ('IOT_ADMIN', 'POST /api/v1/iot/consume-errors/{errorId}/replay'),
    ('IOT_ADMIN', 'POST /api/v1/iot/consume-errors/{errorId}/abandon'),
    ('IOT_ADMIN', 'GET /api/v1/iot/dashboard/summary'),
    ('IOT_ADMIN', 'GET /api/v1/iot/dashboard/wards/{wardId}'),
    ('IOT_ADMIN', 'POST /api/v1/iot/devices'),
    ('IOT_ADMIN', 'GET /api/v1/iot/devices'),
    ('IOT_ADMIN', 'GET /api/v1/iot/devices/{deviceId}'),
    ('IOT_ADMIN', 'POST /api/v1/iot/devices/{deviceId}/credential-reset'),
    ('IOT_ADMIN', 'POST /api/v1/iot/devices/{deviceId}/disable'),
    ('IOT_ADMIN', 'GET /api/v1/iot/devices/{deviceId}/shadow'),
    ('IOT_ADMIN', 'GET /api/v1/iot/gateways'),
    ('IOT_ADMIN', 'POST /api/v1/iot/gateways'),
    ('IOT_ADMIN', 'PUT /api/v1/iot/gateways/{gatewayId}'),
    ('IOT_ADMIN', 'DELETE /api/v1/iot/gateways/{gatewayId}'),
    ('IOT_ADMIN', 'GET /api/v1/iot/linkage-rules'),
    ('IOT_ADMIN', 'POST /api/v1/iot/linkage-rules'),
    ('IOT_ADMIN', 'PUT /api/v1/iot/linkage-rules/{id}'),
    ('IOT_ADMIN', 'DELETE /api/v1/iot/linkage-rules/{id}'),
    ('IOT_ADMIN', 'GET /api/v1/iot/linkage-logs'),
    ('IOT_ADMIN', 'POST /api/v1/iot/linkage-logs/{linkageNo}/retry'),
    ('IOT_ADMIN', 'GET /api/v1/iot/metrics'),
    ('IOT_ADMIN', 'POST /api/v1/iot/metrics'),
    ('IOT_ADMIN', 'GET /api/v1/iot/monitor/consumer-lag'),
    ('IOT_ADMIN', 'POST /api/v1/iot/products'),
    ('IOT_ADMIN', 'POST /api/v1/iot/products/{productId}/model-sync'),
    ('IOT_ADMIN', 'GET /api/v1/iot/products'),
    ('IOT_ADMIN', 'GET /api/v1/iot/products/{productId}'),
    ('IOT_ADMIN', 'PUT /api/v1/iot/products/{productId}/commands'),
    ('IOT_ADMIN', 'GET /api/v1/iot/products/{productId}/commands'),
    ('IOT_ADMIN', 'PUT /api/v1/iot/products/{productId}/metric-mappings'),
    ('IOT_ADMIN', 'GET /api/v1/iot/products/{productId}/metric-mappings'),
    ('IOT_ADMIN', 'GET /api/v1/iot/quality/stats'),
    ('IOT_ADMIN', 'GET /api/v1/iot/quality/device-usage'),
    ('IOT_ADMIN', 'GET /api/v1/iot/telemetry/series'),
    ('IOT_ADMIN', 'GET /api/v1/iot/telemetry/latest'),
    -- A.9 ward 域（20 行：冷链面 IOT_ADMIN + 输注/体征板双角色 + 呼叫面 NURSE）
    ('IOT_ADMIN', 'POST /api/v1/ward/cold-chain/archives'),
    ('IOT_ADMIN', 'GET /api/v1/ward/cold-chain/archives'),
    ('IOT_ADMIN', 'GET /api/v1/ward/cold-chain/archives/{archiveNo}'),
    ('IOT_ADMIN', 'PUT /api/v1/ward/cold-chain/archives/{archiveNo}'),
    ('IOT_ADMIN', 'DELETE /api/v1/ward/cold-chain/archives/{archiveNo}'),
    ('IOT_ADMIN', 'POST /api/v1/ward/cold-chain/archives/{archiveNo}/records'),
    ('IOT_ADMIN', 'GET /api/v1/ward/cold-chain/archives/{archiveNo}/records'),
    ('NURSE', 'GET /api/v1/ward/infusion-history/{deviceId}'),
    ('IOT_ADMIN', 'GET /api/v1/ward/infusion-history/{deviceId}'),
    ('NURSE', 'GET /api/v1/ward/vital-board/{wardId}'),
    ('IOT_ADMIN', 'GET /api/v1/ward/vital-board/{wardId}'),
    ('NURSE', 'POST /api/v1/ward/ward-calls'),
    ('NURSE', 'GET /api/v1/ward/ward-calls'),
    ('NURSE', 'GET /api/v1/ward/ward-calls/{callNo}'),
    ('NURSE', 'POST /api/v1/ward/ward-calls/{callNo}/answer'),
    ('NURSE', 'POST /api/v1/ward/ward-calls/{callNo}/progress'),
    ('NURSE', 'POST /api/v1/ward/ward-calls/{callNo}/complete'),
    ('NURSE', 'POST /api/v1/ward/ward-calls/{callNo}/transfer'),
    ('NURSE', 'POST /api/v1/ward/ward-calls/{callNo}/route'),
    ('NURSE', 'POST /api/v1/ward/ward-calls/{callNo}/cancel')
) AS m(role_code, perm_code)
JOIN system.sys_role r ON r.role_code = m.role_code AND r.deleted = 0
JOIN system.sys_permission p ON p.perm_code = m.perm_code AND p.deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id AND rp.deleted = 0);
-- API 段绑定行数自证：A.1 7 + A.2 60 + A.3 18 + A.4 28 + A.5 21 + A.6 55 + A.7 54 + A.8 52 + A.9 20 = 315 行。

-- ---------------------------------------------------------------- 3. 角色权限绑定·MENU 段（附件 B 32 码展开，42 行）
-- 前端路由守卫消费面（裁定 D2）；billing:refund:approve 为 ADMIN 专属不生成行（侧栏业务角色不可见）。
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1117001000000000000 + row_number() OVER (),
       r.id, p.id
FROM (VALUES
    ('REGISTRAR', 'patient:archive:create'),
    ('DOCTOR', 'patient:archive:create'),
    ('NURSE', 'patient:archive:create'),
    ('REGISTRAR', 'patient:archive:search'),
    ('DOCTOR', 'patient:archive:search'),
    ('NURSE', 'patient:archive:search'),
    ('PHARMACIST', 'patient:archive:search'),
    ('CASHIER', 'billing:charge:settle'),
    ('CASHIER', 'billing:statement:daily-list'),
    ('PHARMACIST', 'pharmacy:drug:maintain'),
    ('PHARMACIST', 'pharmacy:dispense:issue'),
    ('PHARMACIST', 'pharmacy:dispense:return'),
    ('PHARMACIST', 'pharmacy:review:audit'),
    ('PHARMACIST', 'pharmacy:dispense:inpatient'),
    ('REGISTRAR', 'outpatient:registration:register'),
    ('NURSE', 'outpatient:triage:manage'),
    ('DOCTOR', 'outpatient:doctor:consult'),
    ('NURSE', 'nursing:ward:view'),
    ('DOCTOR', 'nursing:ward:view'),
    ('NURSE', 'nursing:execution:perform'),
    ('NURSE', 'nursing:adverse-event:report'),
    ('NURSE', 'nursing:pda:use'),
    ('REGISTRAR', 'inpatient:admission:manage'),
    ('DOCTOR', 'inpatient:admission:manage'),
    ('REGISTRAR', 'inpatient:bed:view'),
    ('DOCTOR', 'inpatient:bed:view'),
    ('NURSE', 'inpatient:bed:view'),
    ('REGISTRAR', 'inpatient:station:view'),
    ('NURSE', 'inpatient:station:view'),
    ('DOCTOR', 'inpatient:transfer:check'),
    ('NURSE', 'inpatient:transfer:check'),
    ('DOCTOR', 'inpatient:discharge:manage'),
    ('IOT_ADMIN', 'iot:product:manage'),
    ('IOT_ADMIN', 'iot:device:manage'),
    ('IOT_ADMIN', 'iot:binding:manage'),
    ('IOT_ADMIN', 'iot:alarm-rule:manage'),
    ('IOT_ADMIN', 'iot:command:issue'),
    ('IOT_ADMIN', 'iot:linkage:manage'),
    ('IOT_ADMIN', 'iot:quality:view'),
    ('NURSE', 'ward:infusion:view'),
    ('NURSE', 'ward:call:handle'),
    ('IOT_ADMIN', 'ward:coldchain:manage')
) AS m(role_code, perm_code)
JOIN system.sys_role r ON r.role_code = m.role_code AND r.deleted = 0
JOIN system.sys_permission p ON p.perm_code = m.perm_code AND p.deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id AND rp.deleted = 0);
-- MENU 段绑定行数自证：附件 B 32 码（1 码 ADMIN 专属零行）×授予角色列展开 = 42 行。

-- ---------------------------------------------------------------- 4. doctordemo 增绑 DOCTOR（sys_user id=3 在案，V704 注释锚；ADMIN 绑定不动，权限只增不减）
INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1117001000000000001, 3, 101
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 3 AND role_id = 101 AND deleted = 0);

-- ---------------------------------------------------------------- 5. 演示账号族（sys_user id 11~15，避开 V303 id=1 / V704 id=3 与 IT 种子 id=2、4）
-- P0 联调初始口令：Fuyun@2026（仅存 bcrypt 哈希，禁明文入库）。
-- 红线注记：该初始口令仅具 dev/test 联调意义——P1 密码策略交付时必须强制改密并评估禁用此种子
--   （BRIEF-PR3-01 §2.6）；P0 无生产部署（计划 §4），风险可控。
INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 11, 'nursedemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'nursedemo');

INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 12, 'pharmdemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'pharmdemo');

INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 13, 'cashierdemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'cashierdemo');

INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 14, 'registrardemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'registrardemo');

INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 15, 'iotdemo', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'iotdemo');

-- 员工档案（与账号一对一，displayName 依赖 sys_employee 行——裁定 D7）
INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 11, 11, 'NURSEDEMO', '护理演示', '护师', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'NURSEDEMO');

INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 12, 12, 'PHARMDEMO', '药学演示', '药师', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'PHARMDEMO');

INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 13, 13, 'CASHIERDEMO', '收费演示', '收费员', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'CASHIERDEMO');

INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 14, 14, 'REGISTRARDEMO', '挂号演示', '挂号员', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'REGISTRARDEMO');

INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 15, 15, 'IOTDEMO', '物联网演示', '物联网管理员', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'IOTDEMO');

-- 账号 → 对应业务角色绑定（e2e 403 正反例需要真实业务角色账号——裁定 D7）
INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1117002000000000001, 11, 102
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 11 AND role_id = 102 AND deleted = 0);

INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1117002000000000002, 12, 103
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 12 AND role_id = 103 AND deleted = 0);

INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1117002000000000003, 13, 104
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 13 AND role_id = 104 AND deleted = 0);

INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1117002000000000004, 14, 105
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 14 AND role_id = 105 AND deleted = 0);

INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1117002000000000005, 15, 106
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 15 AND role_id = 106 AND deleted = 0);

-- ---------------------------------------------------------------- 6. 计数自证
-- 本文件装配：sys_role 6 行（id 101~106）+ sys_role_permission API 段 315 行 + MENU 段 42 行
--   + sys_user_role 6 行（doctordemo 增绑 1 + 演示账号 5）+ sys_user 5 行（id 11~15）+ sys_employee 5 行（id 11~15）；
--   grep -c "^INSERT INTO" 本文件 = 24（角色 6 + 绑定 2 + 增绑 1 + 用户 5 + 员工 5 + 账号角色绑定 5；
--   行首锚定规避本注释自匹配，V1116 计数自证同款口径）。
-- 演示账号不种 nurse_assignment 病区绑定行（V1114 语义=病区绑定归护理域种子与运维通道；
--   nursedemo 的 board 访问 e2e 由 W-40 当班绑定守卫通道另行处理，本迁移不动 nursing 域）。
