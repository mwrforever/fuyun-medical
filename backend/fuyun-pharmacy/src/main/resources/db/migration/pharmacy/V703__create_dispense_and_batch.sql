-- V703：调剂单两表 + 批次账两表（Spec docs/specs/modules/06-pharmacy.md :111/:112/:113/:114）。
-- 批次账最小实现（主控裁决 2）：只承载「发药出库/退药回补」两类动作；采购/调拨/盘点/基数/
-- 效期预警全 out；批次 seed 属运行时数据（IT/演示经 SQL 造数），迁移不预置行。
-- 红线 2：库存以批次账为权威账、stock_ledger 只增——批次数量变更必有对应流水，禁止直改库存。

CREATE TABLE pharmacy.drug_batch (
    id                  BIGINT        PRIMARY KEY,
    drug_id             BIGINT        NOT NULL,        -- 药品引用
    storehouse          VARCHAR(32)   NOT NULL,        -- 库房编码（P1 演示常量 OUTP_PHARM；三级库随 P3）
    batch_no            VARCHAR(64)   NOT NULL,        -- 批次号
    production_date     DATE          NULL,            -- 生产日期（先产先出序键）
    expire_date         DATE          NOT NULL,        -- 有效期至（近效期先出序键）
    quantity            DECIMAL(12,3) NOT NULL CHECK (quantity >= 0), -- 现存数量（基础单位）
    locked_qty          DECIMAL(12,3) NOT NULL DEFAULT 0 CHECK (locked_qty >= 0 AND locked_qty <= quantity), -- 配药锁定数
    location            VARCHAR(32)   NULL,            -- 货位
    supplier_ref        VARCHAR(64)   NULL,            -- 供应商引用（采购域随 P3）
    status              VARCHAR(16)   NOT NULL DEFAULT 'IN_STOCK', -- IN_STOCK/FROZEN/PENDING_DESTROY/EXHAUSTED
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

-- 权威账唯一键（Spec :113：(库房, 药品, 批号) 唯一）
CREATE UNIQUE INDEX uk_batch_storehouse_drug_batchno ON pharmacy.drug_batch (storehouse, drug_id, batch_no) WHERE deleted = 0;
-- FEFO 选批高频路径（近效期先出 + 先产先出序）
CREATE INDEX idx_batch_fefo ON pharmacy.drug_batch (drug_id, storehouse, expire_date, production_date) WHERE deleted = 0 AND status = 'IN_STOCK';

CREATE TRIGGER trg_drug_batch_updated_at BEFORE UPDATE ON pharmacy.drug_batch
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 库存流水（只增表：仅 INSERT 通道，禁 UPDATE/DELETE——批次账变更必有对应流水，结存可重建）
CREATE TABLE pharmacy.stock_ledger (
    id                  BIGINT        PRIMARY KEY,
    storehouse          VARCHAR(32)   NOT NULL,
    drug_id             BIGINT        NOT NULL,
    batch_id            BIGINT        NOT NULL,
    action              VARCHAR(24)   NOT NULL,        -- ISSUE 发药出库 / RETURN_RESTOCK 退药回补
    quantity            DECIMAL(12,3) NOT NULL,        -- 数量（出库负/回补正）
    ref_doc             VARCHAR(64)   NOT NULL,        -- 关联单据（dispense_no）
    operator            VARCHAR(64)   NOT NULL,        -- 经手人
    occurred_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_ledger_batch ON pharmacy.stock_ledger (batch_id, id);

CREATE TABLE pharmacy.dispense (
    id                  BIGINT        PRIMARY KEY,
    dispense_no         VARCHAR(32)   NOT NULL,        -- 调剂单号（D+yyyyMMdd+流水）
    dispense_type       VARCHAR(16)   NOT NULL,        -- OUTPATIENT 门诊发药（住院摆药等值随 P2）
    prescription_id     BIGINT        NOT NULL,        -- 处方 id（引用处方主数据，红线 1 不复制明细为权威）
    rx_no               VARCHAR(32)   NOT NULL,        -- 处方号（检索/事件载荷锚）
    patient_id          BIGINT        NOT NULL,
    visit_id            VARCHAR(14)   NOT NULL,
    storehouse          VARCHAR(32)   NOT NULL,
    picker              VARCHAR(64)   NULL,            -- 调配药师（双签之一）
    verifier            VARCHAR(64)   NULL,            -- 核对药师（双签之二，≠picker，PH-1011）
    issuer              VARCHAR(64)   NULL,            -- 发药签名操作者
    issued_at           TIMESTAMPTZ   NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'CREATED', -- DispenseStatus code
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_dispense_no ON pharmacy.dispense (dispense_no) WHERE deleted = 0;
-- 一处方一张活动发药单（charged 重复投递业务级防重第二道防线，CANCELLED 不占）
CREATE UNIQUE INDEX uk_dispense_rx_active ON pharmacy.dispense (prescription_id)
    WHERE deleted = 0 AND dispense_type = 'OUTPATIENT' AND status <> 'CANCELLED';
CREATE INDEX idx_dispense_rx ON pharmacy.dispense (rx_no) WHERE deleted = 0;
CREATE INDEX idx_dispense_visit_status ON pharmacy.dispense (visit_id, status) WHERE deleted = 0;

CREATE TRIGGER trg_dispense_updated_at BEFORE UPDATE ON pharmacy.dispense
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 调剂明细（追溯码逐码采集落行级——医保「无码不结」与防回流核验依据，Spec :112）
CREATE TABLE pharmacy.dispense_item (
    id                    BIGINT        PRIMARY KEY,
    dispense_id           BIGINT        NOT NULL,
    prescription_item_id  BIGINT        NOT NULL,
    drug_id               BIGINT        NOT NULL,
    item_code             VARCHAR(32)   NOT NULL,
    requested_qty         DECIMAL(12,3) NOT NULL CHECK (requested_qty > 0), -- 应发数
    issued_qty            DECIMAL(12,3) NOT NULL DEFAULT 0, -- 实发数（发药签名回写）
    returned_qty          DECIMAL(12,3) NOT NULL DEFAULT 0, -- 退药数（退药受理回写）
    batch_id              BIGINT        NULL,            -- 选批批次（FEFO 单批足量，拆批分配随 P3）
    batch_no              VARCHAR(64)   NULL,
    trace_codes           TEXT          NOT NULL DEFAULT '[]', -- 追溯码逐码采集（JSON 数组文本，ObjectMapper 读写）
    item_status           VARCHAR(16)   NOT NULL DEFAULT 'NORMAL', -- NORMAL/CANCELLED（发药中明细退场）
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by            VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted               SMALLINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_dispense_item_dispense ON pharmacy.dispense_item (dispense_id) WHERE deleted = 0;
CREATE INDEX idx_dispense_item_rx_item ON pharmacy.dispense_item (prescription_item_id) WHERE deleted = 0;

CREATE TRIGGER trg_dispense_item_updated_at BEFORE UPDATE ON pharmacy.dispense_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
