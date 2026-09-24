-- V808：vital_sign_record 患者维度前导索引（性能修复 PERF-02，2026-09-24 性能与算法优化清单定稿）。
-- 业务意图：工作站体征清单（GET /vital-signs?patientId=）与 PDA 患者摘要每次扫码均按
--   patient_id 过滤本表（VitalSignServiceImpl.listByPatient），V803 仅有 (visit_id, measured_at)
--   与 (ward_id, review_status) 前导索引，患者维度查询只能顺序扫描；体征表为全院持续增长的
--   高速写入表（P2 IoT 接入后写入量放大），随运营年限量级劣化。补 (patient_id, measured_at)
--   复合索引后，患者维度查询计划由顺序扫描 → 索引范围扫描，O(全表) → O(log n + 患者行数)；
--   measured_at 入第二列同时服务 from/to 时间窗口与升序排序需求。查询代码零改动即受益
--   （行为保持，不改既有迁移 V803——A.4.1-3 禁改红线）。
-- 构建形态：普通 CREATE INDEX——Flyway 迁移在事务内执行，CREATE INDEX CONCURRENTLY 不可用于
--   事务块内；P1 阶段表数据量有限，建索引锁表窗口可接受（P2 后大表补索引另行评估并发建方案）。
-- 号段：nursing 专属段 V800–V899 续号（全局最大已应用版本 V807 的下一号，满足 outOfOrder=false
--   乱序守卫）；登记载体 docs/migrations/flyway-version-registry.md 与 CHANGELOG.md 同步更新。

CREATE INDEX idx_vital_sign_patient_time ON nursing.vital_sign_record (patient_id, measured_at);
