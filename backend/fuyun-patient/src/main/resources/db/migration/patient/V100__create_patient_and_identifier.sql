-- V100：患者主索引表 + 患者标识注册表（M02 Spec §4 领域模型前两实体，FU-M02-01/02 载体）。
-- 号段登记：patient 域 V100–V199（CHANGELOG 2026-09-16 条目先登记先占），本文件为 V100。
-- DDL 公共约定（V400 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，A.4.2-9）；
--   deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）；唯一约束用部分唯一索引 WHERE deleted = 0。
-- 加密红线（A.4.2-10 + M02 红线 3）：证件号/手机号/住址 AES-GCM 密文列 *_cipher 落库；
--   证件号/手机号需等值检索配 HMAC 盲索引列 *_hash（CHAR(64) = SHA-256 hex）；密钥仅 env 注入。

CREATE TABLE patient.patient (
    patient_id             BIGINT        PRIMARY KEY,           -- 主索引（雪花，本模块签发；实体 ASSIGN_ID 插入时生成，红线 1 全院唯一）
    name                   VARCHAR(64)   NOT NULL,              -- 姓名（明文存：检索需要；展示侧脱敏）
    name_pinyin            VARCHAR(128)  NULL,                  -- 姓名拼音（弱标识匹配与检索首轮索引）
    sex                    VARCHAR(8)    NOT NULL,              -- 性别（M01 国标字典 code，不自建副本）
    birth_date             DATE          NULL,                  -- 出生日期（无证件临时档案可空）
    ethnicity              VARCHAR(8)    NULL,                  -- 民族（M01 国标字典 code；Spec「nation/ethnicity」取 ethnicity 承载）
    marital_status         VARCHAR(8)    NULL,                  -- 婚姻状况（字典 code）
    occupation             VARCHAR(32)   NULL,                  -- 职业
    blood_type             VARCHAR(8)    NULL,                  -- ABO 血型（字典 code）
    id_card_no_cipher      VARCHAR(256)  NULL,                  -- 身份证号 AES-GCM 密文（Base64；IV 前置由构件保证）
    id_card_no_hash        CHAR(64)      NULL,                  -- 身份证号 HMAC-SHA256 hex 盲索引（等值检索）
    mobile_cipher          VARCHAR(256)  NULL,                  -- 手机号密文（根定位层安全红线：手机号属敏感字段）
    mobile_hash            CHAR(64)      NULL,                  -- 手机号 HMAC 盲索引（按手机号查患者）
    address_cipher         VARCHAR(512)  NULL,                  -- 住址密文（无检索需求，不设 hash）
    status                 VARCHAR(16)   NOT NULL,              -- 状态机：NORMAL/FROZEN/MERGED（M02 §5）
    merged_into_patient_id BIGINT        NULL,                  -- 合并指针（status=MERGED 时非空；读侧归一依据，方案 3.3）
    real_name_flag         BOOLEAN       NOT NULL DEFAULT FALSE,-- 实名标记（FALSE=未实名：授权建档/外部核验降级）
    register_channel       VARCHAR(24)   NOT NULL,              -- 建档渠道：WINDOW/SELF_SERVICE/ONLINE/INPATIENT_REGISTER/EMERGENCY
    archive_source         VARCHAR(24)   NOT NULL DEFAULT 'STANDARD', -- 档案来源：STANDARD/TEMP_ANONYMOUS 急诊无名氏/TEMP_NEWBORN 新生儿临时
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by             VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted                SMALLINT      NOT NULL DEFAULT 0
);

-- 强标识匹配主路径：身份证盲索引等值查（EMPI 第一层，部分索引缩面）
CREATE INDEX idx_patient_id_card_hash ON patient.patient (id_card_no_hash) WHERE deleted = 0 AND id_card_no_hash IS NOT NULL;
-- 手机号盲索引等值查（第二检索路径）
CREATE INDEX idx_patient_mobile_hash ON patient.patient (mobile_hash) WHERE deleted = 0 AND mobile_hash IS NOT NULL;
-- 姓名/拼音检索（弱标识评分候选集与 GET /patients/search）
CREATE INDEX idx_patient_name ON patient.patient (name);
CREATE INDEX idx_patient_name_pinyin ON patient.patient (name_pinyin);
-- 按状态筛查（疑似重复扫描/冻结名单）
CREATE INDEX idx_patient_status ON patient.patient (status) WHERE deleted = 0;

CREATE TRIGGER trg_patient_updated_at BEFORE UPDATE ON patient.patient
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 患者标识注册表
-- 一切介质与证件作为标识挂接主索引（方案 3.2）；(identifier_type, value_hash) 唯一约束防一标识挂多档（并发重复登记最终兜底）。
CREATE TABLE patient.patient_identifier (
    id                     BIGINT        PRIMARY KEY,           -- 雪花 ID（MP ASSIGN_ID）
    patient_id             BIGINT        NOT NULL,              -- 挂接的主索引（合并时整批改挂主档，原值入 merge_record 快照）
    identifier_type        VARCHAR(24)   NOT NULL,              -- ID_CARD/PASSPORT/MILITARY_OFFICER/OTHER_LEGAL/INSURANCE_ELECTRONIC/HEALTH_CARD/VISIT_CARD/MEDICAL_RECORD_NO
    identifier_value_cipher VARCHAR(256) NOT NULL,              -- 标识值密文（AES-GCM；明文禁落库禁日志）
    value_hash             CHAR(64)      NOT NULL,              -- 标识值 HMAC 盲索引（解析服务等值查主键路径）
    card_no                VARCHAR(64)   NULL,                  -- 卡面号（卡类介质：VISIT_CARD/HEALTH_CARD；非卡介质为空）
    status                 VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE', -- 状态机：ACTIVE/LOST/REPLACED/DISABLED（M02 §5）
    is_primary             BOOLEAN       NOT NULL DEFAULT FALSE,-- 主标识标记（同档多条 ACTIVE 标识中至多一条，应用层保证）
    bound_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),-- 绑定时刻
    unbound_at             TIMESTAMPTZ   NULL,                  -- 解绑/失效时刻（LOST/DISABLED 落）
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by             VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted                SMALLINT      NOT NULL DEFAULT 0
);

-- 标识业务唯一：同类型同值仅可挂一档（逻辑删行不占用唯一性；并发重复登记以此兜底 PAT-1002）
CREATE UNIQUE INDEX uk_patient_identifier_type_hash
    ON patient.patient_identifier (identifier_type, value_hash) WHERE deleted = 0;
-- 按档案展开标识清单（GET /patients/{id}/identifiers 与合并快照采集）
CREATE INDEX idx_patient_identifier_patient_id ON patient.patient_identifier (patient_id);
-- 按卡面号查卡（就诊卡挂失/补卡入口）
CREATE INDEX idx_patient_identifier_card_no ON patient.patient_identifier (card_no) WHERE deleted = 0 AND card_no IS NOT NULL;

CREATE TRIGGER trg_patient_identifier_updated_at BEFORE UPDATE ON patient.patient_identifier
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
