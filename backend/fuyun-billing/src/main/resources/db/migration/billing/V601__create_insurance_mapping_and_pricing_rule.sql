-- V601：医保 22 项编码对照 + 计价规则（FU-M13-01 贯标载体 / FU-M13-02 规则配置面，Spec §4）。
-- 贯标硬校验数据前提：ACTIVE 对照行唯一有效（部分唯一索引）；无有效对照项目禁医保结算（服务层守卫）。

CREATE TABLE billing.insurance_mapping (
    id                 BIGINT       PRIMARY KEY,
    charge_item_id     BIGINT       NOT NULL,               -- 项目 id
    map_type           VARCHAR(16)  NOT NULL,               -- TREATMENT 诊疗 / DRUG 药品 / CONSUMABLE 耗材（药品/耗材对照引用 M06 国家码，本行仅登记映射关系）
    nhsa_code          VARCHAR(64)  NOT NULL,               -- 国家医保 22 项编码
    catalog_version    VARCHAR(32)  NOT NULL,               -- 目录版本（快照字段，目录动态更新可重现）
    self_pay_ratio     DECIMAL(5,4) NOT NULL DEFAULT 0 CHECK (self_pay_ratio >= 0 AND self_pay_ratio <= 1), -- 先自付比例（0-1）
    limit_price        BIGINT       NULL CHECK (limit_price IS NULL OR limit_price >= 0),                   -- 医保限价（分，NULL=无限价）
    insurance_pay_type VARCHAR(16)  NOT NULL,               -- 支付属性 CLASS_A 甲/CLASS_B 乙/CLASS_C 丙/SELF_EXPENSE 自费（16 列宽：SELF_EXPENSE 12 字符，8 溢出——2026-09-17 审查修正）
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/EXPIRED
    check_receipt      VARCHAR(255) NULL,                   -- 对照校验回执摘要（贯标辅助校验留痕）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted            SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_mapping_item_active ON billing.insurance_mapping (charge_item_id) WHERE deleted = 0 AND status = 'ACTIVE';
CREATE INDEX idx_mapping_nhsa ON billing.insurance_mapping (nhsa_code) WHERE deleted = 0;

-- 计价规则（Spec §4 pricing_rule）：trigger_type 七值 = 事件驱动分流配置面；
--   规则命中项目集合 item_scope 存 JSON 文本（{"itemClasses":[...],"itemIds":[...]} 二选一或并集，
--   解析失败即配置错误抛异常拒算，禁静默）。DURATION（日切分解）与住院联动触发型登记就绪、
--   消费实现随 M04 事件 P2（not-in-scope 声明）。
CREATE TABLE billing.pricing_rule (
    id            BIGINT       PRIMARY KEY,
    rule_code     VARCHAR(64)  NOT NULL,                    -- 规则编码（业务唯一）
    rule_name     VARCHAR(128) NOT NULL,
    trigger_type  VARCHAR(32)  NOT NULL,                    -- ORDER_CONFIRMED/PRESCRIPTION_EFFECTIVE/EXECUTED/REGISTERED/SCANNED/DURATION/MANUAL
    item_scope    TEXT         NOT NULL,                    -- 项目集合 JSON 文本（见上注释）
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/INACTIVE
    remark        VARCHAR(255) NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_pricing_rule_code ON billing.pricing_rule (rule_code) WHERE deleted = 0;

CREATE TRIGGER trg_insurance_mapping_updated_at BEFORE UPDATE ON billing.insurance_mapping
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_pricing_rule_updated_at BEFORE UPDATE ON billing.pricing_rule
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
