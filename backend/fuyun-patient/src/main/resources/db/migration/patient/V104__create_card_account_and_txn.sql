-- V104：一卡通账户表 + 流水台账（M02 Spec §4 card_account/card_txn，FU-M02-04 台账侧）。
-- 号段登记：patient 域 V100–V199（CHANGELOG 2026-09-16 条目），本文件为 V104。
-- 金额分值制（A.4.2-8）：balance/amount/balance_after 一律 BIGINT 存分，应用层禁浮点。
-- 职责边界（M02 §1 非职责）：资金收退付动作在 M13，本模块仅维护余额台账与流水（M13 发起、本模块记账）。
-- card_txn 为只增台账（V503 定案口径）：无 UPDATE 语义，不挂触发器、不设 deleted。

CREATE TABLE patient.card_account (
    id           BIGINT        PRIMARY KEY,                   -- 雪花 ID（MP ASSIGN_ID）
    patient_id   BIGINT        NOT NULL,                      -- 患者主索引（1:1，一卡通为可选启用项）
    balance      BIGINT        NOT NULL DEFAULT 0,            -- 余额（分；单语句 UPDATE 原子记账防并发超扣）
    status       VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',     -- 状态机：ACTIVE/FROZEN(挂失联动)/CLOSED(销户余额必须为零)
    opened_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),        -- 开户时刻
    closed_at    TIMESTAMPTZ   NULL,                          -- 销户时刻
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted      SMALLINT      NOT NULL DEFAULT 0
);

-- 一人一账户（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_card_account_patient ON patient.card_account (patient_id) WHERE deleted = 0;

CREATE TRIGGER trg_card_account_updated_at BEFORE UPDATE ON patient.card_account
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE patient.card_txn (
    id            BIGINT        PRIMARY KEY,                  -- 雪花 ID（MP ASSIGN_ID）
    account_id    BIGINT        NOT NULL,                     -- 账户 ID（card_account 主键）
    txn_type      VARCHAR(16)   NOT NULL,                     -- RECHARGE 充值/PAY 消费/REFUND 退款/REVERSE 冲正
    amount        BIGINT        NOT NULL,                     -- 金额（分，恒为正数；方向由 txn_type 表达）
    balance_after BIGINT        NOT NULL,                     -- 记账后余额（分；对账核对锚点）
    biz_ref       VARCHAR(64)   NULL,                         -- M13 收费单据引用（记账与资金动作分离的对账关联键）
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),       -- 记账业务时刻
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 按账户时序展开流水（GET /card-accounts/{id}/txns 与按日对账）
CREATE INDEX idx_card_txn_account ON patient.card_txn (account_id, occurred_at);
