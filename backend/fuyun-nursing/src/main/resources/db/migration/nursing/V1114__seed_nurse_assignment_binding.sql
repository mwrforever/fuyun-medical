-- V1114：nurse_assignment 演示/运维账号病区绑定行种子（PR-4C W-40 方案 A / D-29 裁决）。
-- 背景：W-40 fail-closed 上线后无 ACTIVE 绑定行的账号一律 403，须同批为演示/运维账号种绑定行
--   （否则锁死全部账号）。admin=sys_user.id 1、doctordemo=id 3（V303/V704 种子在案）。
-- 语义声明：本两行 assignment_type='PRIMARY' 但 patient_id/bed_no 为 NULL——非护理责任分配，
--   仅承载 W-40 访问授权（应用层 assign 端点的类型一致性校验不适用于种子通道；uk 为部分索引
--   仅覆盖 patient_id/bed_no IS NOT NULL 行，NULL 行不进索引无冲突）。
-- 幂等形态：INSERT...SELECT...WHERE NOT EXISTS（V303 先例）；ID 取固定值（雪花 19 位量级永不冲突）。
-- created_at/updated_at 由 DEFAULT now() 维护——A.4.2-9；表内触发器补 updated_at。
INSERT INTO nursing.nurse_assignment
    (id, ward_id, nurse_id, assignment_type, shift_code, bed_no, patient_id,
     valid_from, valid_to, status, created_by, updated_by, deleted)
-- NULL 列显式定型（NULL::bigint / NULL::date）：UNION ALL 各分支的未定型 NULL 会被 PostgreSQL
--   先按 UNION 规则统一解析为 text，text→bigint/date 无赋值转换上下文（IT 实证报错），须先定型。
SELECT 9114000000000000001, 'W01', '1', 'PRIMARY', 'DAY', NULL, NULL::bigint,
       DATE '2026-01-01', NULL::date, 'ACTIVE', 'V1114', 'V1114', 0
WHERE NOT EXISTS (SELECT 1 FROM nursing.nurse_assignment WHERE nurse_id = '1' AND deleted = 0)
UNION ALL
SELECT 9114000000000000002, 'W01', '3', 'PRIMARY', 'DAY', NULL, NULL::bigint,
       DATE '2026-01-01', NULL::date, 'ACTIVE', 'V1114', 'V1114', 0
WHERE NOT EXISTS (SELECT 1 FROM nursing.nurse_assignment WHERE nurse_id = '3' AND deleted = 0);
