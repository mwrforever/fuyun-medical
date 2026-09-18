-- V600：收费项目库 + 价格版本 + 组合构成（M13 Spec §4 三实体，FU-M13-01 载体；号段 V600–V699
--   见 CHANGELOG 2026-09-17 登记条目）。
-- DDL 公共约定（V100/V400 先例）：审计列 DEFAULT now() + V1 公共触发器；deleted 逻辑删；
--   不建外键（应用层保证）；唯一约束用部分唯一索引 WHERE deleted = 0。
-- 金额红线（A.4.2-8）：price BIGINT 存「分」；quantity 用 DECIMAL(12,3)（服务费量可小数，
--   金额 = 单价×数量 服务端 HALF_UP 取整）。

CREATE TABLE billing.charge_item (
    id            BIGINT       PRIMARY KEY,                 -- 雪花（实体 ASSIGN_ID）
    item_code     VARCHAR(64)  NOT NULL,                    -- 项目编码（院内物价码，业务唯一）
    item_name     VARCHAR(128) NOT NULL,                    -- 项目名称
    item_class    VARCHAR(16)  NOT NULL,                    -- 类别：WEST_DRUG/TRAD_DRUG/TREATMENT/CONSUMABLE/BED/NURSING/OTHER
    unit          VARCHAR(16)  NOT NULL,                    -- 计价单位（次/支/日…，M01 字典 code 引用不建副本）
    exec_dept_id  BIGINT       NULL,                        -- 默认执行科室（M01 组织 id 引用）
    price_flag    VARCHAR(16)  NOT NULL DEFAULT 'SINGLE',   -- SINGLE 可单独收费 / COMBO_ONLY 仅组合内
    combo_flag    BOOLEAN      NOT NULL DEFAULT FALSE,      -- 组合项目标记（划价按构成展开）
    fee_category  VARCHAR(32)  NOT NULL,                    -- 清单费用大类（M01 字典 code，结算清单汇总分组依据）
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/INACTIVE
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_charge_item_code ON billing.charge_item (item_code) WHERE deleted = 0;

-- 价格版本（方案 3.4）：DRAFT 草稿 → PUBLISHED 定时生效（发布同刻闭区间旧版本 effective_to）
--   → EXPIRED 已失效；「当前唯一有效版本」由部分唯一索引硬保证（effective_to IS NULL 形态）
CREATE TABLE billing.charge_item_price (
    id              BIGINT       PRIMARY KEY,
    charge_item_id  BIGINT       NOT NULL,                  -- 项目 id（应用层关联）
    price           BIGINT       NOT NULL CHECK (price >= 0),-- 单价（分，≥0）
    version         INT          NOT NULL,                  -- 版本号（项目内递增，快照回溯锚点）
    effective_from  TIMESTAMPTZ  NOT NULL,                  -- 生效起（发布即生效或定时时刻）
    effective_to    TIMESTAMPTZ  NULL,                      -- 生效止（NULL=当前有效版本）
    price_source    VARCHAR(16)  NOT NULL,                  -- OFFICIAL_DOC 物价批文 / AGREEMENT 协议价
    approval_no     VARCHAR(64)  NULL,                      -- 批文/协议文号
    status          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PUBLISHED/EXPIRED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_price_item_version ON billing.charge_item_price (charge_item_id, version) WHERE deleted = 0;
CREATE UNIQUE INDEX uk_price_item_current ON billing.charge_item_price (charge_item_id) WHERE deleted = 0 AND effective_to IS NULL AND status = 'PUBLISHED';
CREATE INDEX idx_price_item_effective ON billing.charge_item_price (charge_item_id, effective_from) WHERE deleted = 0;

-- 组合构成：组合项目划价展开成员明细，逐成员计价与医保对照（FU-M13-01）
CREATE TABLE billing.charge_item_component (
    id                 BIGINT        PRIMARY KEY,
    combo_item_id      BIGINT        NOT NULL,              -- 组合项目 id
    component_item_id  BIGINT        NOT NULL,              -- 成员项目 id
    default_quantity   DECIMAL(12,3) NOT NULL CHECK (default_quantity > 0), -- 默认数量
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted            SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_combo_member ON billing.charge_item_component (combo_item_id, component_item_id) WHERE deleted = 0;

-- updated_at 触发器（V1 公共函数复用，A.4.2-9 应用层禁写）
CREATE TRIGGER trg_charge_item_updated_at BEFORE UPDATE ON billing.charge_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_charge_item_price_updated_at BEFORE UPDATE ON billing.charge_item_price
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
CREATE TRIGGER trg_charge_item_component_updated_at BEFORE UPDATE ON billing.charge_item_component
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
