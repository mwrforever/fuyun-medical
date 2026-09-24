-- V900：patient.patient 姓名前导通配 trigram GIN 索引（性能修复 PERF-03，2026-09-23 性能与算法优化清单定稿）。
-- 业务意图：患者检索是挂号/建档台最高频入口（GET /patients/search），姓名关键词经
--   PatientServiceImpl.search 生成 name LIKE '%kw%'（前后通配）；V100 idx_patient_name 为普通
--   B-tree，无法服务前导通配谓词，百万级主档只能全表扫描 + 全表 COUNT（分页 total），随档案量
--   增长线性劣化。本迁移启用 pg_trgm 扩展并为 name 建 GIN (gin_trgm_ops) 索引，'%kw%' 谓词由此
--   可走 trigram 索引扫描：O(全表) → O(索引候选集)；查询语句、结果集与排序零变化（行为保持，
--   查询代码与既有迁移 V100 均未触碰——A.4.1-3 禁改红线）。
-- 扩展归属边界（A.4.1-5）：该条款的 initdb 独占口径约束 TimescaleDB 扩展（须容器级预载共享库，
--   见 deploy/postgres/initdb/01-init.sql 注释）；pg_trgm 为 trusted 扩展，经幂等
--   CREATE EXTENSION IF NOT EXISTS 随迁移启用——迁移是唯一能对全环境（compose / IT 容器 /
--   生产托管库）一致保证扩展在位的载体，且 GIN 索引的 opclass 依赖扩展必须先行同迁移落位。
-- 构建形态：普通 CREATE INDEX——Flyway 迁移在事务内执行，CREATE INDEX CONCURRENTLY 不可用于
--   事务块内；P1 阶段表数据量有限，建索引锁表窗口可接受（V808 同款取舍）。
-- 号段：patient 后续迁移走 V500+ 通用段（注册表规则 4），V800–V899 为 nursing 专属段不得占用
--   （注册表规则 8），故取 V900——全局最大已应用版本 V808 之后的首个合法号（outOfOrder=false
--   乱序守卫）；登记载体 docs/migrations/flyway-version-registry.md 与 CHANGELOG.md 同步更新。

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_patient_name_trgm ON patient.patient USING gin (name gin_trgm_ops);
