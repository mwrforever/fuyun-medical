-- V804：M05 出入量明细与小结两表（05-nursing Spec §4 io_record/io_summary；调研依据 6：
--   每班小结出入量、大夜班每 24 小时总结一次并记录在体温单相应栏内、小结与总结红双线标识由前端渲染）
-- P1 生产者仅 MANUAL 与 PDA；INFUSION_AUTO（输液执行自动带入，P2 执行域）/TRANSFUSION_AUTO（M12，P4）/
--   ICU_AUTO·ICU_MANUAL（M11，P4）为枚举预留，P1 无写入方（javadoc 注记）。

CREATE TABLE nursing.io_record (
    id          BIGINT        PRIMARY KEY,
    visit_id    VARCHAR(14)   NOT NULL,                                    -- 住院就诊号（I 型）
    patient_id  BIGINT        NOT NULL,                                    -- 患者 ID
    ward_id     VARCHAR(64)   NOT NULL,                                    -- 病区编码
    occur_at    TIMESTAMPTZ   NOT NULL,                                    -- 发生时间
    io_type     VARCHAR(8)    NOT NULL,                                    -- INTAKE 入量 / OUTPUT 出量
    item_code   VARCHAR(32)   NOT NULL,                                    -- 项目 code（入量：IV_FLUID/ORAL/NASOGASTRIC/BLOOD；出量：URINE/STOOL/VOMIT/DRAINAGE/PUNCTURE）
    item_name   VARCHAR(64)   NOT NULL,                                    -- 项目名称（冗余展示名，字典未建时前端直显）
    quantity    NUMERIC(10,2) NOT NULL,                                    -- 数量（入量 ml、出量 ml；重量类 g）
    unit        VARCHAR(16)   NOT NULL DEFAULT 'ml',                       -- 单位
    source      VARCHAR(24)   NOT NULL,                                    -- MANUAL / PDA / INFUSION_AUTO / TRANSFUSION_AUTO / ICU_AUTO / ICU_MANUAL
    source_ref  VARCHAR(64)   NULL,                                        -- 来源单据引用（输液执行单号等，P2 填）
    shift_code  VARCHAR(32)   NULL,                                        -- 班次 code（取病区班次定义；空=未归属班次）
    recorder_id VARCHAR(64)   NOT NULL,                                    -- 记录人
    remark      VARCHAR(255)  NULL,                                        -- 备注
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by  VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted     SMALLINT      NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.io_record IS '出入量明细账（输液/输血/ICU 自动带入行随 P2/P4 接入）';
CREATE INDEX idx_io_record_visit_time ON nursing.io_record (visit_id, occur_at);
CREATE INDEX idx_io_record_visit_shift ON nursing.io_record (visit_id, shift_code);
CREATE TRIGGER trg_io_record_updated_at BEFORE UPDATE ON nursing.io_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.io_summary (
    id            BIGINT        PRIMARY KEY,
    visit_id      VARCHAR(14)   NOT NULL,                                  -- 住院就诊号（I 型）
    patient_id    BIGINT        NOT NULL,                                  -- 患者 ID
    ward_id       VARCHAR(64)   NOT NULL,                                  -- 病区编码
    summary_type  VARCHAR(8)    NOT NULL,                                  -- SHIFT 班次小结 / 24H 24 小时总结
    period_start  TIMESTAMPTZ   NOT NULL,                                  -- 统计周期起
    period_end    TIMESTAMPTZ   NOT NULL,                                  -- 统计周期止
    total_intake  NUMERIC(12,2) NOT NULL DEFAULT 0,                        -- 总入量
    total_output  NUMERIC(12,2) NOT NULL DEFAULT 0,                        -- 总出量
    balance       NUMERIC(12,2) NOT NULL DEFAULT 0,                        -- 平衡值 = 入量 - 出量
    shift_code    VARCHAR(32)   NULL,                                      -- 班次 code（summary_type=SHIFT 时必填）
    shift_key     VARCHAR(32)   GENERATED ALWAYS AS (COALESCE(shift_code, '')) STORED,  -- 幂等键第四维
    chart_entry_ref BIGINT      NULL,                                      -- 体温单 DAILY_VALUE 条目引用
    recorder_id   VARCHAR(64)   NOT NULL,                                  -- 记录人
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted       SMALLINT      NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.io_summary IS '出入量小结（班次小结与 24 小时总结；体温单红双线标识由前端渲染）';
CREATE UNIQUE INDEX uk_io_summary_period ON nursing.io_summary (visit_id, summary_type, period_start, shift_key) WHERE deleted = 0;
CREATE TRIGGER trg_io_summary_updated_at BEFORE UPDATE ON nursing.io_summary
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
