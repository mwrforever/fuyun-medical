-- V1006：iot_binding / iot_telemetry visit_id 类型改造 CF-3（TASK.md W-10）。
-- 号段说明：原计划号 V406 已废——2026-09-26 迁移号勘误后 iot 增量走通用段（台账
-- docs/migrations/flyway-version-registry.md V1006 行先记再改）；宪法 A.4.1-3 禁改已应用迁移
-- （V400 BIGINT 列），故经本新迁移改造。
-- 背景三步声明（GC15 原文摘要，顺序严格不可调换）：CF-3 冻结的 visit_id =
-- <O|I> + 8 位日期 + 5 位流水定长 14 位字符串（00-implementation-order §5 CF-3 / 02-patient §3.4）；
-- 现库演示夹具行 id=900001（patient_id=1/visit_id=1）为纯外键占位语义、无引用目标（PR-2 调研
-- §1.4 实证）。
--   ① 删除演示夹具行——USING NULL 转换会把存量行 visit_id 置 NULL、违反 NOT NULL，空表是转换前提；
--   ② 两表同批改列类型 VARCHAR(14) USING NULL（iot_telemetry 为 hypertable，空表演示库瞬时完成）；
--   ③ 重插演示夹具行（visit_id 改 14 位字符串形态，I 型住院就诊号样例）。

-- 步骤①：删除演示夹具行（为 USING NULL 转换清出空表前提）
DELETE FROM iot.iot_binding WHERE id = 900001;

-- 步骤②：两表同批改列类型（CF-3 定长 14 位字符串；USING NULL 清空存量——空表演示库无转换损失）
ALTER TABLE iot.iot_binding ALTER COLUMN visit_id TYPE VARCHAR(14) USING NULL;
ALTER TABLE iot.iot_telemetry ALTER COLUMN visit_id TYPE VARCHAR(14) USING NULL;

-- 步骤③：重插演示夹具行。⚠ 演示夹具，非业务数据（列清单按 V400 必填列实测补齐：
-- bed_id/unbind_reason/unbound_at 可空；created_at/updated_at/created_by/updated_by/deleted 由库端默认值承担）
INSERT INTO iot.iot_binding (id, device_id, patient_id, visit_id, ward_id, bind_type, status, bind_reason, bound_by, bound_at)
VALUES (900001, 'fuyun-demo-001', 1, 'I2026090100001', 1, 'FIXED', 'BOUND', '演示夹具', 1, now());
