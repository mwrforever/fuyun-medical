-- V604：住院预交金账户/流水 + 医保业务调用日志（FU-M13-04/05；Spec §4）。
-- 门诊预交金按 2025-03 国家政策取消不设账户（Spec §12 澄清①）；余额由单语句原子 UPDATE 维护
--   （RETURNING 回读，CardAccountMapper.recordBalance 同范式）；流水只增，退回生成对冲行。

CREATE TABLE billing.deposit_account (
    id                BIGINT       PRIMARY KEY,
    patient_id        BIGINT       NOT NULL,
    visit_id          VARCHAR(14)  NOT NULL,                  -- CF-3 住院就诊号（I 前缀，服务层校验）
    balance           BIGINT       NOT NULL DEFAULT 0,        -- 余额（分）
    warning_threshold BIGINT       NOT NULL,                  -- 欠费预警阈值（分，缴存时可覆盖默认参数）
    status            VARCHAR(16)  NOT NULL DEFAULT 'NORMAL', -- NORMAL/ARREARS/SETTLED/CLOSED
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_deposit_visit ON billing.deposit_account (visit_id) WHERE deleted = 0;
CREATE INDEX idx_deposit_patient ON billing.deposit_account (patient_id) WHERE deleted = 0;

CREATE TABLE billing.deposit_txn (
    id              BIGINT       PRIMARY KEY,
    account_id      BIGINT       NOT NULL,
    txn_type        VARCHAR(16)  NOT NULL,                    -- DEPOSIT 缴入/REFUND 退回/OFFSET 结算抵扣
    amount          BIGINT       NOT NULL CHECK (amount > 0), -- 金额（分，恒正，方向由 txn_type）
    payment_method  VARCHAR(16)  NOT NULL,                    -- CASH/BANK/SCAN/ONLINE
    channel_ref     VARCHAR(64)  NULL,                        -- 渠道流水号
    operator        VARCHAR(64)  NOT NULL,                    -- 操作者（登录身份）
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/REFUNDED/OFFSET（ACTIVE 禁改，退回对冲）
    offset_settle_no VARCHAR(32) NULL,                        -- 抵扣归属结算单号（OFFSET 行）
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_deposit_txn_account ON billing.deposit_txn (account_id, occurred_at) WHERE deleted = 0;

-- 医保业务级留痕（方案 3.2 两级日志之业务级：悬挂确认/冲正/补偿驱动数据；通道级在 M20 不重复建）
CREATE TABLE billing.insurance_call_log (
    id               BIGINT       PRIMARY KEY,
    txn_code         VARCHAR(16)  NOT NULL,                   -- 基线版交易码（2001 门诊登记/2002 入院办理/2101 费用上传/2102 预结算/2103 结算/2104 撤销，模拟通道同码）
    visit_id         VARCHAR(14)  NULL,
    settlement_id    BIGINT       NULL,
    request_digest   VARCHAR(512) NOT NULL,                   -- 请求摘要（脱敏，禁完整明文入日志）
    response_digest  VARCHAR(512) NULL,                       -- 应答摘要/回执原文引用摘要（≥10 年留存随 FU-06 归档扩展）
    center_serial_no VARCHAR(64)  NULL,                       -- 中心流水号
    result_code      VARCHAR(16)  NULL,                       -- 中心结果码（模拟=0000 成功）
    result_msg       VARCHAR(255) NULL,
    duration_ms      BIGINT       NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'INIT',    -- INIT/SENT/SUCCESS/FAILED/TIMEOUT/COMPENSATED/WAIVED
    compensate_note  VARCHAR(255) NULL,                       -- 补偿/核销结论（WAIVED 必填）
    trace_id         VARCHAR(64)  NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted          SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX idx_ins_call_visit ON billing.insurance_call_log (visit_id, status) WHERE deleted = 0;
CREATE INDEX idx_ins_call_settlement ON billing.insurance_call_log (settlement_id) WHERE deleted = 0;

CREATE TRIGGER trg_deposit_account_updated_at BEFORE UPDATE ON billing.deposit_account
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_deposit_txn_updated_at BEFORE UPDATE ON billing.deposit_txn
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_insurance_call_log_updated_at BEFORE UPDATE ON billing.insurance_call_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
