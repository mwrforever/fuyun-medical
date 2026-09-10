-- V401：IoT 消费错误日志表（M14 Spec §4 / §9，BRIEF-PR4-01 §2）。
-- 业务定位：AMQP 主链路解析/校验/落库失败消息的统一落点（毒丸隔离——落库留痕后确认抛弃，不阻塞消费队列）；
--   本表不经 fy.dlx（遥测不走 MQ 总线，总 Spec D2），故独立建表承载留痕。
-- 状态机（14-iot §5）：PENDING 待处理 → REPLAYED 已重放 / ABANDONED 已放弃；REPLAYED 前可多次重放（累加计数）。
-- DDL 公共约定（V300 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数）；不建外键；
--   有状态迁移生命周期（重放/放弃处置）故挂 updated_at 触发器；无 deleted（错误日志无逻辑删语义，
--   处置以 status 终态表达）。
-- 容量口径（14-iot §9）：保留 180 天为容量红线；清理策略 P1 落地（P0 只增不清理）。

CREATE TABLE iot.iot_consume_error_log (
    error_id    BIGINT        PRIMARY KEY,                               -- 雪花 ID（MP ASSIGN_ID）
    queue_name  VARCHAR(128)  NOT NULL,                                  -- 来源订阅队列名（溯源消费链路）
    raw_digest  VARCHAR(64)   NOT NULL,                                  -- 原文 SHA-256 十六进制摘要（DeadLetterListener 同口径）
    raw_payload VARCHAR(4000) NULL,                                      -- 载荷引用（脱敏后截断留痕，禁原文敏感值全量入库）
    error_stage VARCHAR(16)   NOT NULL,                                  -- 失败阶段：PARSE 解析/VALIDATE 校验/PERSIST 落库
    error_msg   VARCHAR(500)  NULL,                                      -- 失败原因摘要（不带原文敏感值）
    status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',                -- 处置状态：PENDING 待处理/REPLAYED 已重放/ABANDONED 已放弃
    replay_count INT          NOT NULL DEFAULT 0,                        -- 重放次数（每次重放累加）
    handled_by  VARCHAR(64)   NULL,                                      -- 处置操作人（未处置为空）
    handled_at  TIMESTAMPTZ   NULL,                                      -- 处置时刻（未处置为空）
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by  VARCHAR(64)   NOT NULL DEFAULT 'system'
);

-- 按处置状态圈定待处理清单（管理端"可查可重放"主查询路径，created_at 排序定位积压）
CREATE INDEX idx_iot_consume_error_log_status_created ON iot.iot_consume_error_log (status, created_at);
-- 按来源队列与时间定位某条订阅链路的失败分布（队列级质量排障路径）
CREATE INDEX idx_iot_consume_error_log_queue_created ON iot.iot_consume_error_log (queue_name, created_at);

CREATE TRIGGER trg_iot_consume_error_log_updated_at BEFORE UPDATE ON iot.iot_consume_error_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
