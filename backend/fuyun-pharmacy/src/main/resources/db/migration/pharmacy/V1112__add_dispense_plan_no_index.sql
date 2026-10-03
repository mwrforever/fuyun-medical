-- V1112：住院链 receive/acceptInpatientReturn 按 dispense_plan_no 等值点查（门诊行 NULL 不进索引）。
-- 业务意图（C-4 索引补课，PR-4 评审遗留调研 r3 §二设计候选）：V1110 为 pharmacy.dispense 加
-- dispense_plan_no VARCHAR(32) 住院回链列（门诊行 NULL）但零索引，V703 既有四索引
-- （uk_dispense_no / uk_dispense_rx_active / idx_dispense_rx / idx_dispense_visit_status）均不覆盖
-- 新列。DispensePlanServiceImpl 两处住院链点查——receive 签收按 planNo 定位调剂行迁 DELIVERED、
-- acceptInpatientReturn 退药定位——lambdaQuery().eq(Dispense::getDispensePlanNo, planNo) 等值点查
-- （MP 逻辑删自动追加 deleted=0），当前只能全表顺序扫描；dispense 承载门诊发药全量为长期增长表，
-- 演示期无感、生产必要，属低成本前置止血。补部分索引后点查由顺序扫描 → 索引点查，
-- O(全表) → O(log n + 计划关联行数)；部分谓词 dispense_plan_no IS NOT NULL 使门诊行（恒 NULL）
-- 不入索引——索引体量随住院摆药量而非发药总量增长；查询代码零改动即受益（行为保持，
-- 不改既有迁移 V703/V1110——A.4.1-3 禁改红线）。
-- 构建形态：普通 CREATE INDEX——Flyway 迁移在事务内执行，CREATE INDEX CONCURRENTLY 不可用于
--   事务块内；当前阶段表数据量有限，建索引锁表窗口可接受（V808/V900/V1103~V1105 同款取舍）。
-- 号段：pharmacy 后续迁移走 V500+ 通用段（注册表规则 4，V1000/V1110/V1111 先例），取 V1112——
--   全局最大已应用版本 V1111 的下一号（outOfOrder=false 乱序守卫）；登记载体
--   docs/migrations/flyway-version-registry.md 与 CHANGELOG.md 同步更新。

CREATE INDEX idx_dispense_dispense_plan_no ON pharmacy.dispense (dispense_plan_no)
    WHERE dispense_plan_no IS NOT NULL AND deleted = 0;
