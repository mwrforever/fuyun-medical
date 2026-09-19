-- V701：处方主数据两表（Spec docs/specs/modules/06-pharmacy.md :106/:107）。
-- 处方主数据唯一权威源在本模块（模块红线 1）：rx_no 由本模块签发；计费行快照随
-- pharmacy.prescription.created 事件唯一携带（M-4 裁决），杜绝处方费用双头生成。
-- 状态机（Spec :132 逐字冻结，值域=PrescriptionStatus code）：CREATED→APPROVED（预检通过级
-- 同事务）→PENDING_FEE→PENDING_DISPENSE→DISPENSING→DISPENSED→PART/FULL_RETURNED；
-- APPROVED/PENDING_FEE→CANCELLED（未缴费作废联动费用作废）；REJECTED/CANCELLED 终态。

CREATE TABLE pharmacy.prescription (
    id                  BIGINT        PRIMARY KEY,
    rx_no               VARCHAR(32)   NOT NULL,        -- 处方号（R+yyyyMMdd+流水，本模块签发）
    rx_type             VARCHAR(16)   NOT NULL,        -- OUTPATIENT/EMERGENCY/DISCHARGE/INTERNET（后两值预留，PR-4 拒开）
    patient_id          BIGINT        NOT NULL,        -- 患者主索引（M02）
    visit_id            VARCHAR(14)   NOT NULL,        -- CF-3 门诊就诊号（O 型 14 位）
    doctor              VARCHAR(64)   NOT NULL,        -- 开方医生（登录上下文操作者）
    dept_code           VARCHAR(32)   NULL,            -- 开方科室
    diagnosis_codes     VARCHAR(255)  NULL,            -- 诊断引用（M01 字典 code 逗号分隔）
    rx_category         VARCHAR(16)   NOT NULL DEFAULT 'NORMAL', -- 处方类别 NORMAL/NARCOTIC/PSYCHOTIC_I/PSYCHOTIC_II/TOXIC（随药品毒麻类别派生）
    skin_test_required  BOOLEAN       NOT NULL DEFAULT false, -- 皮试要求（请求显式 OR 任一明细药品需皮试）
    rx_source           VARCHAR(16)   NOT NULL DEFAULT 'DOCTOR_STATION', -- DOCTOR_STATION/DISCHARGE_CONVERT（后者 PR-4 拒开）
    source_order_no     VARCHAR(32)   NULL,            -- 源医嘱号（出院带药转换引用，PR-4 恒 NULL）
    valid_until         TIMESTAMPTZ   NULL,            -- 有效截止（当日有效，参数化随 P3；PR-4 不承载时效拦截）
    review_level        VARCHAR(16)   NOT NULL,        -- 预检分级 PASS/WARNING/REJECT（PR-4 恒 PASS，结构预留）
    status              VARCHAR(24)   NOT NULL,        -- PrescriptionStatus code
    cancel_reason       VARCHAR(255)  NULL,            -- 作废原因（CANCELLED 必填）
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_rx_no ON pharmacy.prescription (rx_no) WHERE deleted = 0;
-- 药房队列与退药检索高频路径（GET /prescriptions?visitId=|patientId=|rxNo=，Spec :169）
CREATE INDEX idx_rx_visit_status ON pharmacy.prescription (visit_id, status) WHERE deleted = 0;
CREATE INDEX idx_rx_patient ON pharmacy.prescription (patient_id) WHERE deleted = 0;

CREATE TRIGGER trg_prescription_updated_at BEFORE UPDATE ON pharmacy.prescription
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 处方明细（计费行快照载体：item_code+数量+用法摘要随 created 事件携带，Spec :107）
CREATE TABLE pharmacy.prescription_item (
    id                  BIGINT        PRIMARY KEY,
    prescription_id     BIGINT        NOT NULL,        -- 所属处方 id
    drug_id             BIGINT        NOT NULL,        -- 药品引用（drug 字典）
    drug_code           VARCHAR(32)   NOT NULL,        -- 院内码快照
    item_code           VARCHAR(32)   NOT NULL,        -- 计费行快照·收费项目 code（M13 权威引用，NULL 药品开方即拒）
    quantity            DECIMAL(12,3) NOT NULL CHECK (quantity > 0), -- 数量（计费与发药共用口径）
    unit                VARCHAR(16)   NULL,            -- 单位
    single_dose         VARCHAR(64)   NULL,            -- 单次剂量
    route_code          VARCHAR(32)   NULL,            -- 给药途径（M01 字典 code；须 ∈ drug.route_codes）
    frequency           VARCHAR(32)   NULL,            -- 用药频次（M01 字典 code，PR-4 仅非空校验）
    days                INT           NULL,            -- 用药天数
    usage_note          VARCHAR(255)  NULL,            -- 用法备注
    usage_summary       VARCHAR(255)  NOT NULL,        -- 用法摘要（created 事件计费行携带，服务端拼装）
    skin_test_flag      BOOLEAN       NOT NULL DEFAULT false, -- 明细级皮试要求快照
    returned_quantity   DECIMAL(12,3) NOT NULL DEFAULT 0, -- 已退数量（退药回写）
    status              VARCHAR(16)   NOT NULL DEFAULT 'NORMAL', -- NORMAL/CANCELLED（发药中明细退场）
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

CREATE INDEX idx_rx_item_rx ON pharmacy.prescription_item (prescription_id) WHERE deleted = 0;
-- 明细只读（Spec :107：改方=驳回后重新开立新版本），returned_quantity/status 由退药链回写

CREATE TRIGGER trg_prescription_item_updated_at BEFORE UPDATE ON pharmacy.prescription_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
