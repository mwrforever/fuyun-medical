-- V103：隐私授权 + 脱敏规则 + 敏感查阅留痕三表（M02 Spec §4，FU-M02-06 载体），并落 5 行默认脱敏规则种子。
-- 号段登记：patient 域 V100–V199（CHANGELOG 2026-09-16 条目），本文件为 V103。
-- privacy_access_log 为只增表（V503 定案口径）：无 UPDATE 语义，不挂触发器、不设 deleted。

CREATE TABLE patient.privacy_auth (
    id           BIGINT        PRIMARY KEY,                   -- 雪花 ID（MP ASSIGN_ID）
    patient_id   BIGINT        NOT NULL,                      -- 患者主索引（建档必须存在有效知情同意，应用层校验）
    auth_type    VARCHAR(16)   NOT NULL,                      -- INFORMED_CONSENT 建档知情同意/SENSITIVE_USE 敏感信息单独同意/GUARDIAN 监护人代管
    auth_basis   VARCHAR(255)  NOT NULL,                      -- 授权依据引用（纸质凭证编号/电子签名引用，签发经 M01 CA）
    scope        VARCHAR(255)  NULL,                          -- 授权范围（如科研/外送用途说明）
    signed_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),        -- 签署时刻
    valid_to     TIMESTAMPTZ   NULL,                          -- 失效时刻（空=长期有效；到期 EXPIRED 由读侧派生）
    status       VARCHAR(16)   NOT NULL DEFAULT 'EFFECTIVE',  -- 状态机：EFFECTIVE/EXPIRED(到期自动，读侧派生)/REVOKED(撤回)
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted      SMALLINT      NOT NULL DEFAULT 0
);

-- 按患者展开授权清单（建档知情同意校验与 GET /privacy-auths）
CREATE INDEX idx_privacy_auth_patient ON patient.privacy_auth (patient_id, auth_type) WHERE deleted = 0;

CREATE TRIGGER trg_privacy_auth_updated_at BEFORE UPDATE ON patient.privacy_auth
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 脱敏规则集中配置：各端展示统一生效（方案定稿；exempt_roles 豁免角色经 M01 RBAC 角色编码逗号分隔）
CREATE TABLE patient.privacy_mask_rule (
    id           BIGINT        PRIMARY KEY,                   -- 雪花 ID（MP ASSIGN_ID）
    rule_code    VARCHAR(32)   NOT NULL,                      -- 规则编码（业务唯一，种子固定 5 条）
    target_field VARCHAR(24)   NOT NULL,                      -- 目标字段：name/idCardNo/mobile/address/birthDate
    mask_pattern VARCHAR(32)   NOT NULL,                      -- 保留策略：KEEP_FIRST/KEEP_6_4/KEEP_3_4/KEEP_PROVINCE_CITY/KEEP_YEAR
    exempt_roles VARCHAR(255)  NOT NULL DEFAULT '',           -- 豁免角色集合（逗号分隔角色编码；空串=无人豁免）
    enabled      BOOLEAN       NOT NULL DEFAULT TRUE,         -- 启用标记
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted      SMALLINT      NOT NULL DEFAULT 0
);

-- 规则编码业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_privacy_mask_rule_code
    ON patient.privacy_mask_rule (rule_code) WHERE deleted = 0;

CREATE TRIGGER trg_privacy_mask_rule_updated_at BEFORE UPDATE ON patient.privacy_mask_rule
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 默认脱敏规则种子（幂等 INSERT WHERE NOT EXISTS，V5 先例；id 1-5 为种子专用小整数，V403 同口径；
-- 豁免角色初值仅 ADMIN——业务必需场景（挂号收费等）按 M01 RBAC 角色编码随运营配置经 PUT /privacy-mask-rules/{ruleCode} 增补）
INSERT INTO patient.privacy_mask_rule (id, rule_code, target_field, mask_pattern, exempt_roles, enabled)
SELECT s.id, s.rule_code, s.target_field, s.mask_pattern, s.exempt_roles, s.enabled
FROM (VALUES
    (1, 'MASK_NAME',       'name',      'KEEP_FIRST',          'ADMIN', TRUE),
    (2, 'MASK_ID_CARD_NO', 'idCardNo',  'KEEP_6_4',            'ADMIN', TRUE),
    (3, 'MASK_MOBILE',     'mobile',    'KEEP_3_4',            'ADMIN', TRUE),
    (4, 'MASK_ADDRESS',    'address',   'KEEP_PROVINCE_CITY',  'ADMIN', TRUE),
    (5, 'MASK_BIRTH_DATE', 'birthDate', 'KEEP_YEAR',           'ADMIN', TRUE)
) AS s(id, rule_code, target_field, mask_pattern, exempt_roles, enabled)
WHERE NOT EXISTS (SELECT 1 FROM patient.privacy_mask_rule WHERE rule_code = s.rule_code);

CREATE TABLE patient.privacy_access_log (
    id          BIGINT        PRIMARY KEY,                   -- 雪花 ID（MP ASSIGN_ID）
    operator_id VARCHAR(64)   NOT NULL,                      -- 操作人（OperatorContextHolder 注入）
    patient_id  BIGINT        NOT NULL,                      -- 被查阅患者
    access_type VARCHAR(16)   NOT NULL,                      -- UNMASK_QUERY 明文查阅/ARCHIVE_EXPORT 档案导出/PANORAMA_VIEW 全景调阅
    purpose     VARCHAR(255)  NOT NULL,                      -- 查阅目的（必填，个保法最小必要留痕）
    fields      VARCHAR(255)  NOT NULL,                      -- 查阅字段清单（逗号分隔）
    occurred_at TIMESTAMPTZ   NOT NULL DEFAULT now(),        -- 查阅时刻（业务时刻）
    trace_id    VARCHAR(64)   NULL,                          -- 全链路追踪号（MDC 取值）
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 按患者回溯查阅历史（GET /privacy-access-logs，等保审计主检索）
CREATE INDEX idx_privacy_access_log_patient ON patient.privacy_access_log (patient_id, occurred_at);
