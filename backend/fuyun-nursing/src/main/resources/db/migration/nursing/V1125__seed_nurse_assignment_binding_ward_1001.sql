-- V1125：nurse_assignment 演示账号补 1001 病区授权行（册 0 P-GBL-02 止血配套 / T0-15 fix round 1）。
-- 背景：P-GBL-02 止血（listOrgs WARD 档过滤非纯数字 orgCode）使 W01 退出全站病区下拉后，
--   /nursing/ward、/nursing/execution、/inpatient/station 三页病区下拉回落首项 1001，而
--   admin（nurse_id='1'）/doctordemo（nurse_id='3'）当班绑定集（V1114 种子）仅含 W01，被
--   W-40 fail-closed 守卫拒绝（NS-1028 → 403）。本迁移为两演示账号各补一行 ward_id='1001'
--   ACTIVE 授权行消除 403；既有 W01 行不动、V801/V1123 的 W01 锚不动（迁移禁改红线
--   A.4.1-3）——W01↔纯数字病区码双世界的根治仍归 TASK.md D-32 待决策。
-- 语义声明：本两行照 V1114 种子形态——assignment_type='PRIMARY' 但 patient_id/bed_no 为
--   NULL，非护理责任分配，仅承载 W-40 访问授权；uk_assignment_bed_shift/uk_assignment_
--   patient_shift 为部分唯一索引且仅覆盖 bed_no/patient_id IS NOT NULL 的行（V801:47-50），
--   NULL 授权行不入索引，与既有 W01 行并存无唯一冲突。
-- ID 取值：1125 前缀分段固定值（V1123/V1124 段位顺延风格，19 位内固定值防撞雪花量级）。
-- 幂等形态：INSERT...SELECT...WHERE NOT EXISTS（V1114 先例），防重键取 nurse_id+ward_id 对
--   （同 nurse_id 已有 W01 行，V1114 按 nurse_id 单键防重的口径不适用于本迁移），重放零行插入。
-- created_at/updated_at 由 DEFAULT now() 维护——A.4.2-9；表内触发器补 updated_at。
INSERT INTO nursing.nurse_assignment
    (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,
     valid_from, valid_to, status, created_by, updated_by, deleted)
-- NULL 列显式定型（NULL::bigint / NULL::date）：UNION ALL 各分支的未定型 NULL 会被 PostgreSQL
--   先按 UNION 规则统一解析为 text，text→bigint/date 无赋值转换上下文（IT 实证报错），须先定型。
SELECT 1125000000000000001, '1001', '1', 'PRIMARY', 'DAY', NULL, NULL::bigint,
       DATE '2026-01-01', NULL::date, 'ACTIVE', 'V1125', 'V1125', 0
WHERE NOT EXISTS (SELECT 1 FROM nursing.nurse_assignment WHERE nurse_id = '1' AND ward_id = '1001' AND deleted = 0)
UNION ALL
SELECT 1125000000000000002, '1001', '3', 'PRIMARY', 'DAY', NULL, NULL::bigint,
       DATE '2026-01-01', NULL::date, 'ACTIVE', 'V1125', 'V1125', 0
WHERE NOT EXISTS (SELECT 1 FROM nursing.nurse_assignment WHERE nurse_id = '3' AND ward_id = '1001' AND deleted = 0);
