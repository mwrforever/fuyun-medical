-- V102：基础健康档案 1:1 聚合表 + 明细项子表（M02 Spec §4，FU-M02-05 载体）。
-- 号段登记：patient 域 V100–V199（CHANGELOG 2026-09-16 条目），本文件为 V102。
-- 纠错留痕（FU-M02-05）：纠错不改原记录——原行置 CORRECTED 并新增行，correct_of_item_id 指向被纠错原行。

CREATE TABLE patient.health_summary (
    id                 BIGINT        PRIMARY KEY,            -- 雪花 ID（MP ASSIGN_ID）
    patient_id         BIGINT        NOT NULL,               -- 患者主索引（1:1，uk 兜底）
    blood_type         VARCHAR(8)    NULL,                   -- 血型（字典 code；档案口径，与 patient.blood_type 人口属性列并存）
    rh_type            VARCHAR(8)    NULL,                   -- RH 血型：POSITIVE/NEGATIVE
    past_history       TEXT          NULL,                   -- 既往史
    family_history     TEXT          NULL,                   -- 家族史
    summary_updated_at TIMESTAMPTZ   NULL,                   -- 摘要最近变更时刻（应用层落，事件载荷时间锚点）
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted            SMALLINT      NOT NULL DEFAULT 0
);

-- 一档一份聚合档案（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_health_summary_patient ON patient.health_summary (patient_id) WHERE deleted = 0;

CREATE TRIGGER trg_health_summary_updated_at BEFORE UPDATE ON patient.health_summary
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE patient.health_item (
    id                BIGINT        PRIMARY KEY,             -- 雪花 ID（MP ASSIGN_ID）
    patient_id        BIGINT        NOT NULL,                -- 患者主索引
    item_type         VARCHAR(16)   NOT NULL,                -- ALLERGY 过敏/CHRONIC 慢病/SURGERY 手术/VACCINATION 免疫接种
    item_code         VARCHAR(64)   NULL,                    -- 过敏物/ICD 诊断/疫苗 code（引用 M01 字典，不自建副本）
    item_name         VARCHAR(128)  NOT NULL,                -- 项目名称（录入原文，检索与展示主依据）
    severity          VARCHAR(16)   NULL,                    -- 严重程度（过敏项：MILD/MODERATE/SEVERE）
    onset_date        DATE          NULL,                    -- 发生日期
    status            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE', -- ACTIVE 有效/CORRECTED 已纠错（纠错置旧行）
    source            VARCHAR(16)   NOT NULL,                -- 来源：DOCTOR_STATION 医生站录入/MANUAL 手工补录
    note              VARCHAR(255)  NULL,                    -- 备注
    correct_of_item_id BIGINT       NULL,                    -- 纠错链：本行是对该行的纠错重录（首录为空）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 过敏项快速校验主路径（M06 审方/开单嵌查：按患者取 ACTIVE 过敏项）
CREATE INDEX idx_health_item_patient_type ON patient.health_item (patient_id, item_type) WHERE deleted = 0 AND status = 'ACTIVE';

CREATE TRIGGER trg_health_item_updated_at BEFORE UPDATE ON patient.health_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
