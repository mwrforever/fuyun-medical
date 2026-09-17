-- V602：费用明细（M13 全模块核心表；方案 3.4 快照列组 + Spec §3.1 计费唯一键防重）。
-- 快照列（unit_price_snapshot 等）为冗余冻结列，与版本表不一致时以本表为准（历史正确性优先）；
-- 金额 amount = unit_price_snapshot × quantity 服务端 HALF_UP 取整（红线 1/D5）。
-- billing_key = patient_id|source_ref|trigger_point|charge_item_id|billing_date 服务端拼装，
--   部分唯一索引硬拦「患者+来源单据+计费点+项目+计费日」重复计费（调研依据 3 官方规范四要素的
--   患者维度落地：手工通道 source_ref=操作者工号，不入患者会跨患者误拦，事件通道 orderId 天然
--   患者内唯一，两种来源语义均为「同一患者同一来源单据同项目同日一次」）。

CREATE TABLE billing.fee_record (
    id                        BIGINT        PRIMARY KEY,
    fee_no                    VARCHAR(32)   NOT NULL,        -- 费用编号（F+服务端生成序列号，P1 演示=纳秒后缀，生产可切业务序列；非资金键）
    patient_id                BIGINT        NOT NULL,        -- 患者主索引（M02）
    visit_id                  VARCHAR(14)   NOT NULL,        -- CF-3 定长就诊号（O|I+8 位日期+5 位流水）
    visit_type                VARCHAR(8)    NOT NULL,        -- OUT/IN/PEIS（体检 P6 预留）
    charge_item_id            BIGINT        NOT NULL,
    item_name_snapshot        VARCHAR(128)  NOT NULL,        -- 项目名称快照
    unit_price_snapshot       BIGINT        NOT NULL,        -- 单价快照（分）
    quantity                  DECIMAL(12,3) NOT NULL CHECK (quantity > 0),
    amount                    BIGINT        NOT NULL CHECK (amount >= 0), -- 金额（分，服务端计算）
    fee_category_snapshot     VARCHAR(32)   NOT NULL,        -- 清单费用大类快照（一日清单/结算清单分组）
    charge_source             VARCHAR(24)   NOT NULL,        -- ORDER_LINKED/EXEC_LINKED/DAY_CUTOVER/MANUAL/PEIS
    source_ref                VARCHAR(128)  NOT NULL,        -- 来源单据引用（医嘱/执行单/申请单号/手工=操作者工号，红线 3 可追溯）
    trigger_point             VARCHAR(32)   NOT NULL,        -- 计费点（开单/执行/处方生效/日切/手工）
    billing_date              DATE          NOT NULL,        -- 计费日（唯一键第四要素）
    nhsa_code_snapshot        VARCHAR(64)   NULL,            -- 医保对照编码快照（无有效对照=NULL，仅自费）
    catalog_version_snapshot  VARCHAR(32)   NULL,            -- 目录版本快照
    self_pay_ratio_snapshot   DECIMAL(5,4)  NULL,            -- 先自付比例快照
    limit_price_snapshot      BIGINT        NULL,            -- 限价快照
    billing_key               VARCHAR(256)  NOT NULL,        -- 计费唯一键（服务端拼装 patient_id|source_ref|trigger_point|charge_item_id|billing_date，重复计费拦截硬防线；256 列宽：五要素+四分隔符理论上限约 212——source_ref 128/trigger 32/两 id 各 19/日期 10，192 可溢出，2026-09-17 审查修正）
    price_version             INT           NOT NULL,        -- 价格版本号快照（快照不漂移 IT 锚点）
    settlement_id             BIGINT        NULL,            -- 结算单 id（未结算 NULL）
    exec_occupy_status        VARCHAR(16)   NOT NULL DEFAULT 'NONE', -- NONE/DISPENSED/EXECUTED/UPLOADED（执行占用，退费硬前置）
    status                    VARCHAR(16)   NOT NULL DEFAULT 'PENDING', -- PENDING/CONFIRMED/SETTLED/PART_REFUND/FULL_REFUND/CANCELLED/GUARANTEED/BAD_DEBT（后两值绿通挂账 P1 后启用）
    operator                  VARCHAR(64)   NULL,            -- 手工计费操作者（charge_source=MANUAL 必填，服务层守卫）
    manual_reason             VARCHAR(255)  NULL,            -- 手工计费理由（同上必填）
    charged_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by                VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by                VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted                   SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_fee_no ON billing.fee_record (fee_no) WHERE deleted = 0;
-- 计费唯一键防重：作废行不占键（当日更正作废后可重新计费），deleted 逻辑删行同样不占
CREATE UNIQUE INDEX uk_fee_billing_key ON billing.fee_record (billing_key) WHERE deleted = 0 AND status <> 'CANCELLED';
-- 高频读路径（Spec §9 费用查询：visit_id+状态；结算回挂：settlement_id）
CREATE INDEX idx_fee_visit_status ON billing.fee_record (visit_id, status) WHERE deleted = 0;
CREATE INDEX idx_fee_settlement ON billing.fee_record (settlement_id) WHERE deleted = 0;
CREATE INDEX idx_fee_patient ON billing.fee_record (patient_id) WHERE deleted = 0;

CREATE TRIGGER trg_fee_record_updated_at BEFORE UPDATE ON billing.fee_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
