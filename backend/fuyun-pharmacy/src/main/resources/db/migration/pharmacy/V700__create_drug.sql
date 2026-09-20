-- V700：药品字典（M06 领域模型 drug 全字段，Spec docs/specs/modules/06-pharmacy.md :105；
-- 药品业务主数据权威源，国家医保编码对照在本模块维护、M13 引用不双头）。
-- 公共约定同 V300/V600（雪花 BIGINT 主键 / 数据库触发器维护审计列 / 逻辑删 / 部分唯一索引 /
-- 状态列值域=枚举 code）；给药途径集存 M01 字典 code 集合（禁自建副本，Spec :207），
-- 校验语义=开方入参 route_code ∈ drug.route_codes（院内途径集，PR-4 承载面）。

CREATE TABLE pharmacy.drug (
    id                     BIGINT        PRIMARY KEY,
    drug_code              VARCHAR(32)   NOT NULL,        -- 院内码（业务唯一）
    generic_name           VARCHAR(128)  NOT NULL,        -- 通用名
    trade_name             VARCHAR(128)  NULL,            -- 商品名
    pinyin_code            VARCHAR(64)   NULL,            -- 拼音码（检索辅助，维护侧录入）
    dosage_form            VARCHAR(64)   NULL,            -- 剂型
    specification          VARCHAR(128)  NULL,            -- 规格
    manufacturer           VARCHAR(128)  NULL,            -- 生产厂家
    route_codes            VARCHAR(255)  NULL,            -- 给药途径集（M01 dict code 逗号分隔）
    unit                   VARCHAR(16)   NULL,            -- 单位
    split_ratio            DECIMAL(12,3) NULL,            -- 拆零换算（基础单位/包装单位）
    nhsa_code              VARCHAR(64)   NULL,            -- 国家医保药品编码（NULL=未对照：可院内启用但显式标记不可医保结算）
    nhsa_catalog_version   VARCHAR(32)   NULL,            -- 医保目录版本
    nhsa_pay_type          VARCHAR(16)   NULL,            -- 支付属性 JIA/YI/BING/SELF
    essential_flag         BOOLEAN       NOT NULL DEFAULT false, -- 基药标识
    antibio_class          VARCHAR(16)   NOT NULL DEFAULT 'NONE', -- 抗菌药分级 NONE/UNRESTRICTED/RESTRICTED/SPECIAL
    hazard_level           VARCHAR(8)    NOT NULL DEFAULT 'NONE', -- 高警示等级 NONE/A/B/C
    skin_test_flag         BOOLEAN       NOT NULL DEFAULT false, -- 皮试标识（true=需皮试阴性方可使用）
    narcotic_class         VARCHAR(16)   NOT NULL DEFAULT 'NORMAL', -- 毒麻类别 NORMAL/NARCOTIC/PSYCHOTIC_I/PSYCHOTIC_II/TOXIC
    indication             TEXT          NULL,            -- 说明书·适应证（结构化说明书文本承载）
    max_dose               VARCHAR(128)  NULL,            -- 说明书·最大剂量（P3 审方规则引用位）
    contraindication       TEXT          NULL,            -- 说明书·禁忌
    storage_condition      VARCHAR(255)  NULL,            -- 说明书·贮存条件
    item_code              VARCHAR(32)   NULL,            -- 关联 M13 收费项目（NULL=不可计费开方拒）
    trace_code_type        VARCHAR(16)   NULL,            -- 追溯码类型（医保追溯码采集分类）
    status                 VARCHAR(16)   NOT NULL DEFAULT 'ENABLED', -- ENABLED/DISABLED
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by             VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted                SMALLINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_drug_code ON pharmacy.drug (drug_code) WHERE deleted = 0;
-- 选药检索高频路径（GET /drugs/search：名称/拼音/医保码前缀 + 过滤，Spec :168）
CREATE INDEX idx_drug_search ON pharmacy.drug (lower(generic_name), lower(trade_name)) WHERE deleted = 0;
CREATE INDEX idx_drug_nhsa ON pharmacy.drug (nhsa_code) WHERE deleted = 0 AND nhsa_code IS NOT NULL;

CREATE TRIGGER trg_drug_updated_at BEFORE UPDATE ON pharmacy.drug
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
