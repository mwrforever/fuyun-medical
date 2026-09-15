-- V503：主数据分发流水（M20 Spec §4 mdm_dispatch_log，FU-M20-04 补偿重发与分发审计依据）
-- 号段登记：V500 起「先登记先占」；V503 于 CHANGELOG 2026-09-15 号段登记条目后落盘（版本 > 真库最大 V501）。
-- 只增台账：无通用 updated_at 语义（V3/V4 定案口径），created_at 由数据库 DEFAULT now() 维护；
-- dispatched_at 为业务时刻（信封 occurredAt，应用层写入），与 received_event.occurred_at 同口径。
-- target_modules 宽度 1000：模块数 ≤ 20，模块标识 ≤32 字符，余量充分（截断仅作列宽防线）。
CREATE TABLE integration.mdm_dispatch_log (
    id             BIGINT        PRIMARY KEY,
    topic          VARCHAR(32)   NOT NULL,               -- 主数据主题：dict/org/user/param/practice
    version        BIGINT,                               -- 分发版本号（dict 事件载荷 version；占位 schema 主题为 null）
    dispatch_mode  VARCHAR(16)   NOT NULL,               -- 分发模式：BROADCAST 广播（全量重发待 M01 回源接口就绪）
    dispatched_at  TIMESTAMPTZ   NOT NULL,               -- 分发时刻（信封 occurredAt）
    target_modules VARCHAR(1000) NOT NULL DEFAULT '',    -- 分发目标模块清单（逗号分隔；空串=当时无订阅方）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 分发审计主检索：按主题 + 时间窗回溯分发历史（对账与补偿重发的依据）
CREATE INDEX idx_mdm_dispatch_log_topic ON integration.mdm_dispatch_log (topic, dispatched_at);
