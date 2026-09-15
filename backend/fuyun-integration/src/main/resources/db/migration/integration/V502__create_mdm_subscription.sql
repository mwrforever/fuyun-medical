-- V502：主数据分发订阅台账（M20 Spec §4 mdm_subscription，FU-M20-04 分发关系与对账依据）
-- 号段登记：V500 起「先登记先占」（CHANGELOG 2026-09-08 条目）；V502 于 CHANGELOG 2026-09-15 号段登记条目后落盘，
-- 版本号大于真库历史最大 V501（Flyway outOfOrder=false 拒绝低位新迁移，PR-1a 真栈实证）。
-- 字段全集 = Spec §4 原文字段（topic/subscriber_module/sync_mode/last_version/last_sync_at/last_recon_at/recon_status）
-- 随表落盘防后续 ALTER（V3/V4 同口径）；审计列与逻辑删为项目跨切约定（A.4.2-9 + V2 同型）。
-- updated_at 由 V1 公共触发器函数维护：订阅登记后的对账写路径（last_* / recon_status）产生 UPDATE 生命周期。
CREATE TABLE integration.mdm_subscription (
    id                BIGINT        PRIMARY KEY,
    topic             VARCHAR(32)   NOT NULL,                     -- 主数据主题：dict/org/user/param/practice（M20 §4）
    subscriber_module VARCHAR(32)   NOT NULL,                     -- 订阅方模块域标识（如 system/patient）
    sync_mode         VARCHAR(16)   NOT NULL,                     -- 同步方式：EVENT_SUBSCRIBE 事件订阅 / API_PULL 接口拉取
    last_version      BIGINT,                                     -- 订阅方已同步到的版本号（dict 事件载荷 version 口径）
    last_sync_at      TIMESTAMPTZ,                                -- 最近一次同步时刻（对账写路径，P0 留空）
    last_recon_at     TIMESTAMPTZ,                                -- 最近一次对账时刻（对账任务写路径，P0 留空）
    recon_status      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',   -- 对账状态：PENDING 待对账（登记初值）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 订阅关系业务唯一：同主题同订阅方仅一行（逻辑删行不占用唯一性，V2 同口径）
CREATE UNIQUE INDEX uk_mdm_subscription_topic_subscriber
    ON integration.mdm_subscription (topic, subscriber_module) WHERE deleted = 0;

-- updated_at 触发器：复用 V1 公共函数，应用层禁止写入该列（backend 宪法 A.4.2-9）
CREATE TRIGGER trg_mdm_subscription_updated_at BEFORE UPDATE ON integration.mdm_subscription
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
