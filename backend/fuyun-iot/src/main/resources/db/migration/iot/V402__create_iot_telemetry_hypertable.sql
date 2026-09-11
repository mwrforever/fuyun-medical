-- V402：遥测明细超表 + 压缩/保留策略（M14 Spec §3.2，BRIEF-PR4-01 §1.2 全序列）。
-- 号段登记（BRIEF-PR4-01 §1.1）：iot 域 V400–V499，本文件为 V402。
-- T-R3-2 实测结论（2026-09-10，timescale/timescaledb:2.29.2-pg16 探针容器，证据留存于本批 PR 描述）：
--   探针 SQL「SELECT proname FROM pg_proc WHERE proname IN
--   ('add_columnstore_policy','add_compression_policy') ORDER BY 1;」输出两行均在；
--   pg_proc.prokind 实测：add_columnstore_policy = p（过程，须 CALL 调用）、add_compression_policy = f
--   （函数，自 2.18.0 起弃用）。两函数并存以非弃用者为胜 → 本迁移压缩策略以
--   CALL add_columnstore_policy('iot.iot_telemetry', INTERVAL '7 days') 落盘；
--   保留策略 add_retention_policy 实测 prokind = f（SELECT 函数），非 T-R3-2 比对项，照常 SELECT 调用。
--   同款探针在样例超表上 CALL add_columnstore_policy 成功落 policy_compression 作业、
--   SELECT add_retention_policy 落 policy_retention 作业（timescaledb_information.jobs 实证）。
-- DDL 顺序（宪法 A.4.1-5，严格不可调换）：CREATE TABLE → create_hypertable（按天分区）→ 唯一索引
--   （含分区列）→ 压缩开启（compress + segmentby/orderby）→ 压缩策略 → 保留策略 → 补充索引。
-- 只增口径（V302 audit_log 先例）：遥测明细无审计列、无 updated_at 触发器、无 deleted（只增不更新，
--   时间列由 DEFAULT 承担；写入幂等由三列唯一索引 ON CONFLICT 冲突忽略承担）。
-- 分区红线：唯一约束必须含分区列 occurred_at（TimescaleDB 硬约束）；该索引即写入幂等载体（14-iot §4）。
-- 扩展声明：CREATE EXTENSION 不在本迁移（deploy/postgres/initdb 承担，宪法 A.4.1-5）。

CREATE TABLE iot.iot_telemetry (
    device_id   VARCHAR(64)  NOT NULL,                                  -- IoTDA 设备标识（自然键，关联 iot_device）
    patient_id  BIGINT       NULL,                                      -- 患者 ID（写入时绑定快照，无绑定为空）
    visit_id    BIGINT       NULL,                                      -- 就诊 ID（写入时绑定快照，无绑定为空）
    metric_code VARCHAR(128) NOT NULL,                                  -- 指标编码（P0 未建 iot_metric_dict，原生编码直传）
    value       NUMERIC      NOT NULL,                                  -- 采集值（CF-7 value 字符串解析定型；非法数值按 BAD 不阻断）
    unit        VARCHAR(32)  NULL,                                      -- 计量单位（可空）
    occurred_at TIMESTAMPTZ  NOT NULL,                                  -- 采集发生时刻（分区键，按天 chunk）
    quality     VARCHAR(16)  NOT NULL,                                  -- 质量：GOOD/SUSPECT/BAD（值域 = CF-7）
    source      VARCHAR(16)  NOT NULL                                   -- 来源：IOTDA/HL7（P0 全部 IOTDA，值域 = CF-7）
);

-- 按天分区（14-iot §3.2：常态约 432 万行/天，1 天 chunk 大小适中，压缩与保留均以 chunk 为单位批量执行）
SELECT create_hypertable('iot.iot_telemetry', 'occurred_at', chunk_time_interval => INTERVAL '1 day');

-- 三列唯一索引（含分区列 occurred_at）——TimescaleDB 唯一约束硬约束 + 写入幂等载体
CREATE UNIQUE INDEX uk_iot_telemetry_device_metric_time ON iot.iot_telemetry (device_id, metric_code, occurred_at);

-- 列存压缩开启：按设备+指标分段、按发生时间倒序排序（与最高频查询"单设备单指标时间段"模式一致）
ALTER TABLE iot.iot_telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric_code',
    timescaledb.compress_orderby = 'occurred_at DESC'
);

-- 压缩策略：发生时间超过 7 天的 chunk 由后台作业自动压缩（T-R3-2 实测胜者函数，见文件头；过程须 CALL）
CALL add_columnstore_policy('iot.iot_telemetry', INTERVAL '7 days');

-- 保留策略：原始明细保留 90 天自动删除（14-iot §3.2 定稿；聚合 1 年随 P1 连续聚合交付）
SELECT add_retention_policy('iot.iot_telemetry', INTERVAL '90 days');

-- 患者维度查询前置索引（P1"患者 7 天体温曲线"类页面路径，属建表内含索引）
CREATE INDEX idx_iot_telemetry_patient ON iot.iot_telemetry (patient_id, occurred_at DESC);
