-- V203：门诊申请单两表（M03 Spec §4 医生站开单，Task 8）：clinic_order（申请单主单——检查/检验/
-- 治疗/处置/材料/处方引用六类，order_no=OP+yyyyMMdd+6 位流水业务号，billing sourceRef 直取）、
-- clinic_order_item（申请单明细行——item_code=M13 物价库 code，红线不自建价格；quantity 为
-- DECIMAL string 文本承载，D-18 同源）。
-- 公共约定同 V200/V201/V202 形态（雪花 ID / 数据库维护审计列 / 逻辑删 / 无外键 / 唯一索引 /
-- 状态列值域=枚举 code 且词表入列注释）；资金无涉红线（裁决 7）：两表零金额列——fee_settlement_id
-- 仅存结算单回填锚，费用金额与结算一律 M13 权威。

-- ---------------------------------------------------------------- 申请单主表
-- 医生站开单主单（order.created id 23 发布载体）：CREATED 开立→PENDING_FEE 费用生成→CHARGED
-- 缴费放行→IN_EXECUTION 执行中（声明态，P3 执行回执触发）/COMPLETED 执行完成（声明态）→
-- CANCELLED 作废；RX_REF 处方引用行由 M06 处方生效链写入（ext_ref=M06 rxNo，P1 仅 RX_REF 写）。
CREATE TABLE outpatient.clinic_order (
    id                BIGINT       PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    order_no          VARCHAR(32)  NOT NULL,                        -- 申请单业务号（OP+yyyyMMdd+6 位流水，uk_order_no）
    visit_id          VARCHAR(14)  NOT NULL,                        -- 就诊号（uk_visit_id 同源，开单前置 visit 非终态守卫）
    patient_id        BIGINT       NOT NULL,                        -- 患者主索引（visit 同源冗余，order.created 载荷直取）
    order_type        VARCHAR(16)  NOT NULL,                        -- 单据类型：EXAM 检查/LAB 检验/TREATMENT 治疗/DISPOSAL 处置/MATERIAL 材料/RX_REF 处方引用
    ext_ref           VARCHAR(64)  NULL,                            -- 外部单据引用（rx_ref=M06 rxNo；P1 仅 RX_REF 写）
    order_doctor_id   VARCHAR(64)  NOT NULL,                        -- 开单医生 id（执业授权强校验通过者，OperatorContextHolder）
    valid_to          TIMESTAMPTZ  NULL,                            -- 执行有效期（P1 为空，执行域随 P3）
    status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED',      -- CREATED 已开立/PENDING_FEE 待缴费/CHARGED 已缴费/IN_EXECUTION 执行中（声明态）/COMPLETED 执行完成（声明态）/CANCELLED 已作废
    fee_settlement_id BIGINT       NULL,                            -- 结算单 id（M13 回填锚，退费逆向定位）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);

-- 申请单业务号唯一锚（billing sourceRef 对账与作废定位维度）
CREATE UNIQUE INDEX uk_order_no ON outpatient.clinic_order (order_no);

-- 按就诊号取申请单清单（医生站开单/诊毕在途单据校验/作废定位路径）
CREATE INDEX idx_clinic_order_visit ON outpatient.clinic_order (visit_id) WHERE deleted = 0;

CREATE TRIGGER trg_clinic_order_updated_at BEFORE UPDATE ON outpatient.clinic_order
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 申请单明细表
-- 计费行明细（order.created lines 逐行展开来源）：item_code=M13 物价库 code（红线不自建价格，
-- 定价随 M13 计价引擎）；quantity 为 DECIMAL string 文本（D-18 数量出入参 string 承载，禁数值列
-- 精度漂移）；usage_summary 用法摘要（处方引用行以外可空）。
CREATE TABLE outpatient.clinic_order_item (
    id            BIGINT       PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    order_id      BIGINT       NOT NULL,                            -- 申请单主键（clinic_order.id）
    item_code     VARCHAR(64)  NOT NULL,                            -- 项目编码（M13 物价库 code，计价与执行定位锚）
    quantity      VARCHAR(32)  NOT NULL,                            -- 数量（DECIMAL string，如 "2"/"0.5"，红线禁数值列）
    usage_summary VARCHAR(255) NULL,                                -- 用法摘要（频次/途径/注意事项快照，可空）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

-- 按申请单取明细行（VO 组装与 order.created lines 展开）
CREATE INDEX idx_clinic_order_item_order ON outpatient.clinic_order_item (order_id) WHERE deleted = 0;

CREATE TRIGGER trg_clinic_order_item_updated_at BEFORE UPDATE ON outpatient.clinic_order_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
