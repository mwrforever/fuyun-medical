-- V603：结算单 + 退费申请 + 退费费用关联（FU-M13-03；Spec §4/§5）。
-- 医保拆分五列全部按预结算回执落库（红线 1：本地不自行计算基金拆分；模拟适应器回执同构）。
-- 已结算不可变（红线 2）：SETTLED 后应用层拒改；退费以负向 fee_record + link 表达，本表只追加
--   状态迁移列。payment_details 存 JSON 文本（[{method,amount,channelRef}]，金额勾稽第三层校验源）。

CREATE TABLE billing.settlement (
    id                   BIGINT       PRIMARY KEY,
    settle_no            VARCHAR(32)  NOT NULL,               -- 结算编号（S+服务端生成序列号，与 fee_no 同口径；幂等锚点=本列终态）
    patient_id           BIGINT       NOT NULL,
    visit_id             VARCHAR(14)  NOT NULL,               -- CF-3 定长就诊号
    settle_type          VARCHAR(8)   NOT NULL,               -- OUT/IN（出院结算并入 IN + fee_period 区分，PEIS 预留）
    payer_type           VARCHAR(16)  NOT NULL,               -- SELF_PAY/CITY_INS/PROV_INS/OUTSIDE_INS/COMM_INS（商保预留）
    fee_start            TIMESTAMPTZ  NOT NULL,               -- 费用期起
    fee_end              TIMESTAMPTZ  NOT NULL,               -- 费用期止
    total_amount         BIGINT       NOT NULL CHECK (total_amount >= 0), -- 应结总额（分）
    pooled_amount        BIGINT       NOT NULL DEFAULT 0,     -- 统筹支付（分，医保回执）
    acct_pay_amount      BIGINT       NOT NULL DEFAULT 0,     -- 个账支付（分）
    self_pay_amount      BIGINT       NOT NULL DEFAULT 0,     -- 自付（分，先自付+比例自付）
    self_expense_amount  BIGINT       NOT NULL DEFAULT 0,     -- 自费（分，目录外）
    pre_self_pay_amount  BIGINT       NOT NULL DEFAULT 0,     -- 先自付（分）
    payment_details      TEXT         NOT NULL DEFAULT '[]',  -- 支付方式明细 JSON（支付合计=总额勾稽）
    ins_settle_no        VARCHAR(64)  NULL,                   -- 医保中心结算流水号（回执）
    ins_receipt_ref      BIGINT       NULL,                   -- 回执原文引用（insurance_call_log.id，红线 5）
    catalog_version      VARCHAR(32)  NULL,                   -- 目录版本（异地「就医地目录」留痕）
    status               VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- DRAFT/PRESETTLED/SETTLED/REFUNDED/RED_REVERSED/CANCELLED
    settled_at           TIMESTAMPTZ  NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted              SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_settle_no ON billing.settlement (settle_no) WHERE deleted = 0;
CREATE INDEX idx_settle_visit ON billing.settlement (visit_id, status) WHERE deleted = 0;

CREATE TABLE billing.refund_request (
    id                  BIGINT       PRIMARY KEY,
    refund_no           VARCHAR(32)  NOT NULL,                -- 退费编号（R+雪花 id）
    settlement_id       BIGINT       NOT NULL,                -- 原结算单
    patient_id          BIGINT       NOT NULL,
    visit_id            VARCHAR(14)  NOT NULL,
    refund_type         VARCHAR(16)  NOT NULL,                -- DAY_CORRECTION 当日更正/CROSS_DAY 跨日/SETTLED_REFUND 已结算退费
    amount              BIGINT       NOT NULL CHECK (amount > 0), -- 申请金额（分，服务端按明细聚合）
    reason              VARCHAR(255) NOT NULL,                -- 退费理由（必填留痕）
    applicant           VARCHAR(64)  NOT NULL,                -- 申请人（登录身份注入）
    approver            VARCHAR(64)  NULL,                    -- 审批人（双人守卫：≠applicant）
    approved_at         TIMESTAMPTZ  NULL,
    payment_refund_ref  VARCHAR(64)  NULL,                    -- 原路退回流水（渠道占位；CARD_BALANCE=台账流水 id）
    ins_reverse_ref     BIGINT       NULL,                    -- 医保撤销回执引用（insurance_call_log.id，SETTLED_REFUND+医保结算必填）
    reject_reason       VARCHAR(255) NULL,
    auto_approved       BOOLEAN      NOT NULL DEFAULT FALSE,  -- 免审直退标识（审计抽查检索键，Spec §9）
    status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING_APPROVAL', -- DRAFT/PENDING_APPROVAL/APPROVED/EXECUTED/REJECTED
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_refund_no ON billing.refund_request (refund_no) WHERE deleted = 0;
CREATE INDEX idx_refund_settlement ON billing.refund_request (settlement_id, status) WHERE deleted = 0;

CREATE TABLE billing.refund_fee_link (
    id               BIGINT        PRIMARY KEY,
    refund_id        BIGINT        NOT NULL,                  -- 退费申请 id
    fee_id           BIGINT        NOT NULL,                  -- 原费用明细 id
    refund_quantity  DECIMAL(12,3) NOT NULL CHECK (refund_quantity > 0), -- 本次退费数量（支持部分退）
    refund_amount    BIGINT        NOT NULL CHECK (refund_amount > 0),    -- 本次退费金额（分）
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted          SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_refund_fee ON billing.refund_fee_link (refund_id, fee_id) WHERE deleted = 0;

CREATE TRIGGER trg_settlement_updated_at BEFORE UPDATE ON billing.settlement
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_refund_request_updated_at BEFORE UPDATE ON billing.refund_request
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_refund_fee_link_updated_at BEFORE UPDATE ON billing.refund_fee_link
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
