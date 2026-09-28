-- V1011：遥测两级连续聚合（FU-M14-06，P2 PR-2 Task 10）——iot.cagg_1min / iot.cagg_1h。
-- 号段说明：iot 增量走通用段（台账 docs/migrations/flyway-version-registry.md V1011 行已排定，
--   P2 PR-2 Task 10 落盘；宪法 A.4.1-3 禁改已应用迁移）。
-- 语法实测结论（2026-09-26，timescale/timescaledb:2.29.2-pg16 探针容器，与 deploy compose 及
--   Testcontainers IotMigrationIT 严格同 tag）：
--   ① add_continuous_aggregate_policy 实测 pg_proc.prokind = f（SELECT 函数），照 V402
--      add_retention_policy 同款 SELECT 调用（非 CALL 过程）；
--   ② brief 原文 CREATE MATERIALIZED VIEW ... WITH (timescaledb.continuous) ... WITH NO DATA
--      对既有超表 iot.iot_telemetry（V402）一次执行通过；
--   ③ 策略落盘后 timescaledb_information.jobs 回填 hypertable_schema='iot'、
--      hypertable_name=<cagg 视图名>、proc_name='policy_refresh_continuous_aggregate'——
--      即本文件幂等谓词的定位锚；
--   ④ 聚合列名为函数默认名 min/max/avg/first/last/count（保留字列，应用查询侧以双引号引用）。
-- 幂等形态（控制器事实条款，V402 直调形态升级）：对象创建 DO 块 to_regclass 判空防重复建、
--   策略调用 WHERE NOT EXISTS（jobs 视图按 proc_name+hypertable_name 定位）防重复登记作业，
--   迁移重放二跑零副作用。
-- 策略参数（14-iot Spec §3.2 原文）：刷新窗口起点 start_offset 3 天、终点预留 end_offset
--   10 分钟（预留近期未定形桶避免对仍在写入的当前桶反复重算，调研依据 10）、调度周期
--   schedule_interval 10 分钟。
-- WITH NO DATA：建视图不回刷历史明细，由策略作业按窗口渐进物化；实时查询（real-time）默认
--   开启，未物化窗口自动联合原始明细，查询语义不受 NO DATA 影响。
-- 查询路由（FU-M14-06 消费面，TelemetryQueryServiceImpl）：≤24h 且 raw 走明细 / 超 24h 或显式
--   granularity 走 cagg / 超 90 天强制 cagg_1h。

DO $$
BEGIN
    IF to_regclass('iot.cagg_1min') IS NULL THEN
        EXECUTE 'CREATE MATERIALIZED VIEW iot.cagg_1min WITH (timescaledb.continuous) AS '
            || 'SELECT time_bucket(''1 minute'', occurred_at) AS bucket, device_id, metric_code, '
            || 'min(value), max(value), avg(value), first(value, occurred_at), last(value, occurred_at), count(*) '
            || 'FROM iot.iot_telemetry WHERE value IS NOT NULL GROUP BY 1,2,3 WITH NO DATA';
    END IF;
    IF to_regclass('iot.cagg_1h') IS NULL THEN
        EXECUTE 'CREATE MATERIALIZED VIEW iot.cagg_1h WITH (timescaledb.continuous) AS '
            || 'SELECT time_bucket(''1 hour'', occurred_at) AS bucket, device_id, metric_code, '
            || 'min(value), max(value), avg(value), first(value, occurred_at), last(value, occurred_at), count(*) '
            || 'FROM iot.iot_telemetry WHERE value IS NOT NULL GROUP BY 1,2,3 WITH NO DATA';
    END IF;
END $$;

SELECT add_continuous_aggregate_policy('iot.cagg_1min',
           start_offset => INTERVAL '3 days',
           end_offset => INTERVAL '10 minutes',
           schedule_interval => INTERVAL '10 minutes')
WHERE NOT EXISTS (
    SELECT 1 FROM timescaledb_information.jobs
    WHERE proc_name = 'policy_refresh_continuous_aggregate'
      AND hypertable_schema = 'iot' AND hypertable_name = 'cagg_1min');

SELECT add_continuous_aggregate_policy('iot.cagg_1h',
           start_offset => INTERVAL '3 days',
           end_offset => INTERVAL '10 minutes',
           schedule_interval => INTERVAL '10 minutes')
WHERE NOT EXISTS (
    SELECT 1 FROM timescaledb_information.jobs
    WHERE proc_name = 'policy_refresh_continuous_aggregate'
      AND hypertable_schema = 'iot' AND hypertable_name = 'cagg_1h');

COMMENT ON VIEW iot.cagg_1min IS '遥测 1 分钟连续聚合（FU-M14-06）：设备×指标×分钟桶 min/max/avg/first/last/count，刷新窗口 3 天/预留 10 分钟/调度 10 分钟';
COMMENT ON VIEW iot.cagg_1h IS '遥测 1 小时连续聚合（FU-M14-06）：设备×指标×小时桶五聚合+计数，超 90 天查询强制路由本视图';
