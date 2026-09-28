-- V1105：clinic_order 处方引用行 ext_ref 部分索引（性能修复 OPT-06，2026-09-28 全仓性能与
-- 代码质量优化清单定稿）。业务意图：处方引用行四支并发收口 CAS 全表扫描根治——
-- ClinicOrderMapper.casRxRefCharged / casCancelRxRef / casMirrorDispensed / casMirrorReturned
-- 均以 ext_ref = ? AND order_type = 'RX_REF' AND deleted = 0 谓词定位行（M13 settlement.completed
-- / M06 prescription.cancelled / dispense.completed / dispense.returned 四类事件消费，每张处方
-- 全生命周期至少触发两至三支），V203 既有索引（uk_order_no / idx_clinic_order_visit）均不含
-- ext_ref，引用行定位只能全表顺序扫描；clinic_order 为门诊开单主表随运营年限持续增长。补
-- (ext_ref) 部分索引后四支 CAS 由顺序扫描 → 索引点查，O(全表) → O(log n + 处方引用行数)；
-- 部分谓词 order_type = 'RX_REF' AND deleted = 0 与四支语句的常量条件严格同构（等值蕴含，
-- 计划器可命中最优），且非处方引用五类单据行（ext_ref 恒 NULL，V203 词表「P1 仅 RX_REF 写」）
-- 不入索引——索引体量随处方量而非开单总量增长；查询代码零改动即受益（行为保持，不改既有
-- 迁移 V203——A.4.1-3 禁改红线）。
-- 构建形态：普通 CREATE INDEX——Flyway 迁移在事务内执行，CREATE INDEX CONCURRENTLY 不可用于
--   事务块内；P1 阶段表数据量有限，建索引锁表窗口可接受（V808/V900/V1103/V1104 同款取舍）。
-- 号段：outpatient 后续迁移走 V500+ 通用段（注册表规则 4），取 V1105——全局最大已应用版本
--   V1104 的下一号（outOfOrder=false 乱序守卫）；登记载体 docs/migrations/flyway-version-registry.md
--   与 CHANGELOG.md 同步更新。

CREATE INDEX idx_clinic_order_ext_ref ON outpatient.clinic_order (ext_ref) WHERE order_type = 'RX_REF' AND deleted = 0;
