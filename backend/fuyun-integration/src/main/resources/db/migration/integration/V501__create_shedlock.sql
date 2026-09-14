-- V501：ShedLock 分布式锁表（宪法 A.5-14：多实例 @Scheduled 强制配 ShedLock，锁表放公共 schema）。
-- 版本取 500 段顺延 iot 400 段：真库历史已含 system 300 段与 iot 400 段，低版本号会被 Flyway 判 out-of-order 拒绝——真栈探针 2026-09-14 实证。
-- DDL 为 ShedLock 官方 JDBC Template provider 推荐结构；usingDbTime() 模式下时间由数据库时钟统一。
CREATE TABLE IF NOT EXISTS shedlock
(
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP    NOT NULL,
  locked_at  TIMESTAMP    NOT NULL,
  locked_by  VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
);
