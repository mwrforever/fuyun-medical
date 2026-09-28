-- V1104：refund_fee_link.fee_id 前导部分索引（性能修复 OPT-02，2026-09-28 全仓性能与代码
-- 质量优化清单定稿）。业务意图：退费可退余额聚合下推 SQL 的驱动侧索引缺失根治——
-- RefundRequestMapper.xml 两支聚合（sumDecided/sumInFlightRefundedFenByFeeIds，PERF-01 下推）
-- 以 l.fee_id IN (...) 驱动 JOIN billing.refund_request 勾稽退费单状态，V603 仅有
-- uk_refund_fee (refund_id, fee_id)（前导列为 refund_id），fee_id 非前导列不可用，聚合对
-- refund_fee_link 只能顺序扫描；退费为资金热路径（apply 侧超可退守卫按批费用行消费本聚合），
-- EXECUTED 终态 link 行随运营年限单调增长。补 (fee_id) 部分索引后，驱动侧由顺序扫描 →
-- 索引点查集，O(全表) → O(log n × IN 集大小)；单列即足——JOIN 键 refund_id 与聚合列
-- refund_amount 仍需回表读取，扩列为 (fee_id, refund_id) 无 index-only 收益（简单优先）。
-- 附带修正：RefundRequestMapper.xml 头注释「索引聚合（refund_fee_link.fee_id 侧驱动）」原与
--   V603 schema 实况矛盾（fee_id 非前导、无可用索引），注释同步改锚本迁移索引实况；
--   XML 内两支 SQL 语句零改动（RefundAggregateSqlGuardTest 逐子句钉死口径不受影响）。
-- 构建形态：普通 CREATE INDEX——Flyway 迁移在事务内执行，CREATE INDEX CONCURRENTLY 不可用于
--   事务块内；P1 阶段表数据量有限，建索引锁表窗口可接受（V808/V900/V1103 同款取舍）。
-- 号段：billing 通用段续号 V1103 的下一号（注册表规则 4；全局最大已应用版本 V1103 的下一号，
--   outOfOrder=false 乱序守卫）；登记载体 docs/migrations/flyway-version-registry.md 与
--   CHANGELOG.md 同步更新。

CREATE INDEX idx_refund_fee_link_fee ON billing.refund_fee_link (fee_id) WHERE deleted = 0;
