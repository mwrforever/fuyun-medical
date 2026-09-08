-- 初始化：业务库由 POSTGRES_DB 环境变量创建，官方镜像以该库为 initdb 脚本的执行上下文，
-- 本脚本只负责 TimescaleDB 扩展（backend A.4.1-5：CREATE EXTENSION 只在 initdb 承担，Flyway 不重复）
CREATE EXTENSION IF NOT EXISTS timescaledb;
