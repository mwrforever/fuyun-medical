-- V3：消费幂等台账表（M20 Spec §4；backend 宪法 A.5-6 两层幂等的最终兜底层）
-- 只增台账：无通用 updated_at 语义，时间列由 DEFAULT now() 与应用层维护，不挂触发器（V2 迁移定案口径）。
-- 数据保留 180 天后归档清理（M20 §9）；归档策略 P1+ 完整化，本迁移不建清理任务。
-- status/fail_reason/retry_count 为 Spec 字段全集随表落盘（避免后续 ALTER）：P0 仅写 PROCESSED，
-- FAILED 与失败登记列由 P1 消费失败登记启用（BRIEF-PR2-01 §8-5，非死代码）。
CREATE TABLE integration.received_event (
    id              BIGINT        PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    event_id        UUID          NOT NULL,                        -- 信封 eventId（全局唯一）
    event_type      VARCHAR(128)  NOT NULL,
    producer        VARCHAR(32)   NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL,
    consumer_module VARCHAR(32)   NOT NULL,                        -- 幂等键第二要素：消费者模块
    status          VARCHAR(16)   NOT NULL,                        -- PROCESSED 已消费（P0 唯一写入值）；FAILED 预留 P1 消费失败登记
    fail_reason     VARCHAR(1000),                                 -- P1 启用（消费失败原因）
    retry_count     INT           NOT NULL DEFAULT 0,              -- P1 启用（消费重试计数）
    received_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,                                   -- 成功登记时由应用层写入 now()
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 幂等最终兜底：同事件同消费者仅一条（M20 Spec §4；backend 宪法 A.5-6）；幂等判定走唯一索引 P99 < 5ms（M20 §9）
CREATE UNIQUE INDEX uk_received_event_event_consumer ON integration.received_event (event_id, consumer_module);

-- 事件维度检索：死信排查与台账审计按事件类型 + 时间窗口查询
CREATE INDEX idx_received_event_event_type ON integration.received_event (event_type, received_at);
