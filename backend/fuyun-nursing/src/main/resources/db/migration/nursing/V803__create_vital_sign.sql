-- V803：M05 生命体征记录表（05-nursing Spec §4 vital_sign_record / 方案 3.2 三源归一 / 红线 4）
-- P1 落地范围：手工（工作站）与 PDA（含一体机直采属移动端手工形态）两源点测，录入即 CONFIRMED；
--   IoT 源（周期拉取/quality 闸门/同窗冲突仲裁/自动转正）与趋势图归 P2——本表 iot_quality/conflict_ref
--   列落位但 P1 无写入方（javadoc 与实现注记 P2），复核流状态机与端点 P1 可达（IT 直接构造待复核行验证）。
-- 唯一约束形态说明：Spec :108「(visit_id, measured_at, 体温部位) 唯一约束防双写」——体温部位为空时
--   参与唯一（生成列 site_key 承载），使非体温项同刻同患者亦不可重复录入。

CREATE TABLE nursing.vital_sign_record (
    id            BIGINT       PRIMARY KEY,
    visit_id      VARCHAR(14)  NOT NULL,                                   -- 住院就诊号（I 型）
    patient_id    BIGINT       NOT NULL,                                   -- 患者 ID
    ward_id       VARCHAR(64)  NOT NULL,                                   -- 病区编码
    measured_at   TIMESTAMPTZ  NOT NULL,                                   -- 测量时点（业务时间一律服务器时间）
    temperature   NUMERIC(4,1) NULL,                                       -- 体温（℃）
    temp_site     VARCHAR(16)  NULL,                                       -- 体温部位：ORAL 口温 / AXILLARY 腋温 / RECTAL 肛温（符号渲染依据）
    site_key      VARCHAR(16)  GENERATED ALWAYS AS (COALESCE(temp_site, '')) STORED,  -- 唯一约束第四维
    pulse         INT          NULL,                                       -- 脉搏（次/分）
    respiration   INT          NULL,                                       -- 呼吸（次/分）
    systolic_bp   INT          NULL,                                       -- 收缩压（mmHg）
    diastolic_bp  INT          NULL,                                       -- 舒张压（mmHg）
    spo2          INT          NULL,                                       -- 血氧饱和度（%）
    weight        NUMERIC(5,2) NULL,                                       -- 体重（kg）
    height        NUMERIC(5,1) NULL,                                       -- 身高（cm）
    pain_score    INT          NULL,                                       -- 疼痛评分（NRS 0-10）
    source        VARCHAR(16)  NOT NULL,                                   -- 数据源：MANUAL 工作站手工 / PDA 移动端（含一体机直采）/ IOT 连续遥测（P2）
    review_status VARCHAR(16)  NOT NULL DEFAULT 'CONFIRMED',               -- PENDING_REVIEW 待复核 / CONFIRMED 已转正 / REJECTED 已驳回
    reviewed_by   VARCHAR(64)  NULL,                                       -- 复核人
    reviewed_at   TIMESTAMPTZ  NULL,                                       -- 复核时间
    abnormal_flag BOOLEAN      NOT NULL DEFAULT false,                     -- 是否越正常范围（观察行归集判定结果快照）
    iot_quality   VARCHAR(16)  NULL,                                       -- IoT 质量标记 GOOD/SUSPECT/BAD（P2 写入方）
    conflict_ref  BIGINT       NULL,                                       -- 同窗冲突对参照记录（P2 写入方）
    remark        VARCHAR(255) NULL,                                       -- 备注（驳回原因等）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.vital_sign_record IS '生命体征记录（三源归一权威记录；体温单权威栏只收 review_status=CONFIRMED 行）';
CREATE UNIQUE INDEX uk_vital_sign_visit_time_site ON nursing.vital_sign_record (visit_id, measured_at, site_key) WHERE deleted = 0;
CREATE INDEX idx_vital_sign_visit_time ON nursing.vital_sign_record (visit_id, measured_at);
CREATE INDEX idx_vital_sign_ward_review ON nursing.vital_sign_record (ward_id, review_status);
CREATE TRIGGER trg_vital_sign_updated_at BEFORE UPDATE ON nursing.vital_sign_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
