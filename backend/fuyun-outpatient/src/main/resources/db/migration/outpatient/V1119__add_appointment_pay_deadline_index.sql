-- V1119：appointment 支付时限扫描部分索引（W-93① = C-F1，PR-4E 收口五路评审；与 W-91 护理侧
--   nurse_id 索引同族统一交付；PR-4D Task 9 落地）。
-- 业务意图：AppointmentServiceImpl.scanAndReleaseTimedOut（W-27 号源超时 tick 惰性扫描兜底）
--   每轮执行 status='RESERVED' AND pay_deadline < now ORDER BY pay_deadline, id LIMIT 500
--   （TICK_SCAN_LIMIT）扫描；V201 既有索引仅 PK / uk_appt_no / uk_appt_patient，均不含
--   pay_deadline——appointment 为只增历史表，周期全表扫描随运营年限持续放大。补 (pay_deadline)
--   部分索引后由顺序扫描 → 索引范围扫描：pay_deadline 升序前导由索引承载，id 次键经小窗排序
--   消化（LIMIT 500 有界）。
-- 谓词同构：部分谓词 deleted = 0 AND status = 'RESERVED' 与扫描常量条件严格同构
--   （Appointment @TableLogic 查询自动附加 deleted=0；等值蕴含，计划器可命中）；
--   TAKEN / CANCELLED / NO_SHOW 终态行与已删行不入索引，仅 PORTAL 渠道在途占位单入索引——
--   索引体量随在途占位单而非只增历史全表增长。
-- 构建形态：普通 CREATE INDEX IF NOT EXISTS（幂等形态）——Flyway 迁移在事务内执行，CREATE INDEX
--   CONCURRENTLY 不可用于事务块内；当前阶段表数据量有限，建索引锁表窗口可接受
--   （V808/V900/V1103~V1105、V1112/V1113 同款取舍）。
-- 号段：outpatient 走 V500+ 通用段，取 V1119——全局最大已应用版本 V1118 的下一号
--   （outOfOrder=false 乱序守卫）；号段占位先记于 CHANGELOG PR-4D 立项条目（先记再占）。
--   查询代码零改动即受益（行为保持，不改既有迁移 V201——A.4.1-3 禁改红线）。

CREATE INDEX IF NOT EXISTS idx_appointment_pay_deadline_reserved ON outpatient.appointment (pay_deadline) WHERE deleted = 0 AND status = 'RESERVED';
