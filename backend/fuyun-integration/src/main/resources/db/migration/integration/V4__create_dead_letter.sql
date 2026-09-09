-- V4：死信台账表（M20 Spec §5：死信统一落库 + 告警；重放/关闭状态机随 P1 死信管理界面交付）
-- 只增台账：P0 仅写 PENDING 行；状态机列（status/replay_count/handler/handle_note/handled_at）为 Spec
-- 字段全集随表落盘不为死代码（BRIEF-PR2-01 §8-5 同口径）。
-- payload 定案口径（BRIEF-PR2-01 §8-6）：payload_body 落原文全文（重放依赖）+ payload_digest 落 SHA-256 摘要
-- （列表页快速比对）。
-- 同一死信重复投递会重复落行：无唯一约束（同 eventId 可因不同消费者多次死信），P1 死信界面完整化时收敛。
CREATE TABLE integration.dead_letter (
    id             BIGINT        PRIMARY KEY,
    source_queue   VARCHAR(128)  NOT NULL,        -- x-death[].queue 来源队列
    routing_key    VARCHAR(128),                  -- 原始路由键（=事件类型）
    event_type     VARCHAR(128),                  -- 信封 eventType；信封不合规时为空
    event_id       VARCHAR(64),                   -- 信封 eventId；信封不合规时为空
    payload_body   TEXT          NOT NULL,        -- 原始消息体全文（重放依赖原文）
    payload_digest VARCHAR(64),                   -- SHA-256 摘要（列表页快速比对）
    fail_reason    VARCHAR(1000) NOT NULL,        -- 死信原因（消费重试耗尽/信封不合规标注）
    first_dead_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING/REPLAYED/CLOSED 状态机（M20 §5）
    replay_count   INT           NOT NULL DEFAULT 0,          -- P1 死信管理界面启用
    handler        VARCHAR(64),                   -- 处理人（P1 启用）
    handle_note    VARCHAR(500),                  -- 处理备注（P1 启用）
    handled_at     TIMESTAMPTZ,                   -- 处理时间（P1 启用）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 死信管理界面主检索：按状态 + 时间窗口出队处理
CREATE INDEX idx_dead_letter_status ON integration.dead_letter (status, first_dead_at);

-- 事件维度检索：按信封 eventId 溯源全链路死信记录
CREATE INDEX idx_dead_letter_event_id ON integration.dead_letter (event_id);
