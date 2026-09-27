-- V1012：数据质量监控两表 + MDC 字典标称频率增列（FU-M14-11，P2 PR-2 Task 10）——
--   iot_data_quality_stat / iot_consumer_stat / iot_metric_dict.nominal_freq_per_min。
-- 号段说明：iot 增量走通用段（台账 docs/migrations/flyway-version-registry.md V1012 行已排定，
--   P2 PR-2 Task 10 落盘；宪法 A.4.1-3 禁改已应用迁移）。
-- DDL 公共约定（V400/V401 先例）：不建外键；审计列由数据库维护（DEFAULT now() + V1 公共触发器）。
--   iot_data_quality_stat 有 UPSERT 更新路径（当日统计随查询惰性重算覆盖）→ 挂 updated_at 触发器；
--   无 deleted（统计行无逻辑删语义，重算以 UPSERT 覆盖表达）。
--   iot_consumer_stat 为时序快照只增表（V402 只增口径先例）：采样恒 INSERT 不 UPDATE，无审计列、
--   无 updated_at 触发器、无 deleted；同一消费组同一采样时刻唯一（UK 防重复采样落行）。
-- 字典增列语义（本文件头声明）：nominal_freq_per_min = 设备该指标的标称采集频率（次/分钟），
--   FU-M14-11 断流判定与缺数统计的推算基准——断流 = 设备在线但超标称周期 N 倍时长无数据
--   （TelemetryAnomalyPayload 异常面）；expected_count = 标称频率 × 在线时长（14-iot §4）。
--   可空：未登记标称频率的指标不参与缺数推算（缺数率按 0 记，防误报）。
-- 种子 UPDATE（幂等形态：仅回填 NULL 行，人工运维覆写值不被迁移重放冲掉）：两示例指标按
--   体征 spot-check 常规采集节奏标称每分钟 1 个采样点。

CREATE TABLE iot.iot_data_quality_stat (
    device_id      VARCHAR(64)  NOT NULL,                              -- IoTDA 设备标识（自然键，关联 iot_device）
    stat_date      DATE         NOT NULL,                              -- 统计归属自然日（设备本地时区按 UTC 日切）
    expected_count BIGINT       NOT NULL DEFAULT 0,                    -- 期望采样数（标称频率×在线分钟数推算，未登记标称频率为 0）
    received_count BIGINT       NOT NULL DEFAULT 0,                    -- 实际接收采样数（iot_telemetry 当日行数）
    missing_rate   NUMERIC(5,4) NOT NULL DEFAULT 0,                    -- 缺数率（0~1；期望为 0 时恒 0 防误报）
    anomaly_count  BIGINT       NOT NULL DEFAULT 0,                    -- 异常值数（当日 quality != GOOD 行数：SUSPECT+BAD）
    quality_score  NUMERIC(5,2) NOT NULL DEFAULT 0,                    -- 质量得分（0~100：100×(1-缺数率)×(1-异常率)）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system'
);

-- 设备×自然日唯一（统计行惰性重算的 UPSERT 冲突目标，14-iot §4 统计主体粒度）
CREATE UNIQUE INDEX uk_iot_data_quality_stat_device_date ON iot.iot_data_quality_stat (device_id, stat_date);
-- 按统计日圈定全院当日质量面（质量看板按日聚合路径）
CREATE INDEX idx_iot_data_quality_stat_date ON iot.iot_data_quality_stat (stat_date);

CREATE TRIGGER trg_iot_data_quality_stat_updated_at BEFORE UPDATE ON iot.iot_data_quality_stat
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE iot.iot_consumer_stat (
    id                  BIGINT        PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    consumer_group      VARCHAR(128)  NOT NULL,                        -- 消费组/队列标识（本地攒批消费链固定组）
    sampled_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),          -- 采样时刻（与消费组联合唯一防重复采样）
    oldest_msg_age_secs BIGINT        NULL,                            -- 最旧未消费消息年龄秒（IoTDA 侧真实积压，本地不可得随联调补全）
    consume_rate        NUMERIC(12,4) NULL,                            -- 消费速率（条/秒，本地不可得随联调补全）
    arrive_rate         NUMERIC(12,4) NULL,                            -- 到达速率（条/秒，本地不可得随联调补全）
    backlog_estimate    NUMERIC(12,4) NULL                             -- 积压水位估计（本地口径=攒批队列填充率 0~1，真实水位随联调补全）
);

-- 同一消费组同一采样时刻至多一行（采样幂等锚点，重复采样以最新值 UPSERT 语义外的防重约束承载）
CREATE UNIQUE INDEX uk_iot_consumer_stat_group_sampled ON iot.iot_consumer_stat (consumer_group, sampled_at);
-- 按采样时刻倒序取最新快照（积压监控查询主路径，>5 分钟告警判据的数据源）
CREATE INDEX idx_iot_consumer_stat_sampled ON iot.iot_consumer_stat (sampled_at DESC);

-- ---------------------------------------------------------------- MDC 字典标称频率增列（幂等形态）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'iot' AND table_name = 'iot_metric_dict'
          AND column_name = 'nominal_freq_per_min'
    ) THEN
        ALTER TABLE iot.iot_metric_dict ADD COLUMN nominal_freq_per_min NUMERIC NULL;
    END IF;
END $$;

COMMENT ON COLUMN iot.iot_metric_dict.nominal_freq_per_min IS '标称采集频率（次/分钟，FU-M14-11 断流判定与缺数推算基准；NULL=未登记不参与推算）';

-- 种子标称频率（幂等：仅回填 NULL 行，不覆盖人工运维值）
UPDATE iot.iot_metric_dict SET nominal_freq_per_min = 1
WHERE metric_code IN ('MDC_ECG_HEART_RATE', 'MDC_PULSE_OXIM_SPO2')
  AND nominal_freq_per_min IS NULL;

-- ---------------------------------------------------------------- 列注释（词表入列注释）
COMMENT ON TABLE iot.iot_data_quality_stat IS '遥测数据质量日统计（FU-M14-11）：按设备×自然日聚合缺数率/异常值数/质量得分，随查询惰性重算';
COMMENT ON COLUMN iot.iot_data_quality_stat.expected_count IS '期望采样数（设备指标字典标称频率×在线分钟数推算；未登记标称频率为 0）';
COMMENT ON COLUMN iot.iot_data_quality_stat.received_count IS '实际接收采样数（iot_telemetry 当日该设备行数）';
COMMENT ON COLUMN iot.iot_data_quality_stat.missing_rate IS '缺数率 0~1（期望为 0 时恒 0——无标称频率设备不参与缺数判定，防误报）';
COMMENT ON COLUMN iot.iot_data_quality_stat.anomaly_count IS '异常值数（当日 quality != GOOD 行数：SUSPECT 可疑+BAD 异常）';
COMMENT ON COLUMN iot.iot_data_quality_stat.quality_score IS '质量得分 0~100（100×(1-缺数率)×(1-异常率)，异常率=异常值数/接收数）';
COMMENT ON TABLE iot.iot_consumer_stat IS '消费积压监控快照（FU-M14-01 积压观测面）：随监控查询惰性采样落表，时序只增';
COMMENT ON COLUMN iot.iot_consumer_stat.consumer_group IS '消费组/队列标识（本地攒批消费链固定组 iot-amqp）';
COMMENT ON COLUMN iot.iot_consumer_stat.oldest_msg_age_secs IS '最旧未消费消息年龄秒（IoTDA 侧真实积压水位，本地不可得随联调补全）';
COMMENT ON COLUMN iot.iot_consumer_stat.consume_rate IS '消费速率（条/秒，本地不可得随联调补全）';
COMMENT ON COLUMN iot.iot_consumer_stat.arrive_rate IS '到达速率（条/秒，本地不可得随联调补全）';
COMMENT ON COLUMN iot.iot_consumer_stat.backlog_estimate IS '积压水位估计（本地口径=攒批队列填充率 0~1，>阈值分级告警判据）';
