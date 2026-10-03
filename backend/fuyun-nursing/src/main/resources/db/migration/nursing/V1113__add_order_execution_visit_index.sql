-- V1113：casCancelByVisit/casRedirectWard 两支批量 CAS 首要谓词（visit_id 等值；status IN 残余行个位数不进索引）。
-- 业务意图（C-5 索引补课，PR-4 评审遗留调研 r3 §二设计候选）：V1106 为 nursing.order_execution 建
-- 五索引（uk_execution_no / uk_execution_plan / idx_execution_ward_status_time / idx_execution_patient /
-- idx_execution_confirm_status）+ pkey，均无 visit_id 承载。OrderExecutionMapper 两支批量 CAS——
-- casCancelByVisit 出院终清、casRedirectWard 转科改病区——均以 visit_id = ? 为首要等值谓词
-- （附 ward_id 等值与 status IN ('CREATED','SIGNED','CHECKED')，事件驱动，每次出院/转科触发），
-- 当前只能全表顺序扫描。补单列 visit_id 部分索引后由顺序扫描 → 索引点查，等值命中后残余行 =
-- 单就诊在途执行单（个位数），status IN 无需进索引（调研 r3 §二备选 (visit_id, status) 复合
-- 不采纳——IN 列表在第二列选择性有限，收益边际，单列足够）；查询代码零改动即受益（行为保持，
-- 不改既有迁移 V1106——A.4.1-3 禁改红线）。
-- 构建形态：普通 CREATE INDEX——Flyway 迁移在事务内执行，CREATE INDEX CONCURRENTLY 不可用于
--   事务块内；当前阶段表数据量有限，建索引锁表窗口可接受（V808/V900/V1103~V1112 同款取舍）。
-- 号段：nursing 后续迁移走 V500+ 通用段（注册表规则 4，固定段 V800–V899 已被守卫封死禁用），
--   取 V1113——全局最大已应用版本 V1112 的下一号（outOfOrder=false 乱序守卫）；登记载体
--   docs/migrations/flyway-version-registry.md 与 CHANGELOG.md 同步更新。

CREATE INDEX idx_execution_visit ON nursing.order_execution (visit_id) WHERE deleted = 0;
