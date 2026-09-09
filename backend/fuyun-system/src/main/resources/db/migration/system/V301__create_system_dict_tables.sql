-- V301：字典三表（M01 Spec §4，BRIEF-PR3-01 §2.2/§2.4；PR-3 B3.2 交付字典管理业务代码，本批先落表）
-- 公共约定同 V300（雪花 ID / 数据库维护审计列 / 逻辑删 / 无外键 / 部分唯一索引 / 状态列值域 = 枚举 code）。

-- ---------------------------------------------------------------- 字典类型表
CREATE TABLE system.dict_type (
    id               BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    type_code        VARCHAR(64)   NOT NULL,                            -- 字典类型编码，业务唯一（如 gender、nationality）
    type_name        VARCHAR(128)  NOT NULL,                            -- 字典类型名称
    national_standard BOOLEAN      NOT NULL DEFAULT false,              -- 国标字典标记（true=编码不可修改，FU-M01-06）
    remark           VARCHAR(255)  NULL,                                -- 备注
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted          SMALLINT      NOT NULL DEFAULT 0
);

-- 字典类型编码业务唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_dict_type_type_code ON system.dict_type (type_code) WHERE deleted = 0;

CREATE TRIGGER trg_dict_type_updated_at BEFORE UPDATE ON system.dict_type
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 字典版本表
-- 版本化发布模型（M01 §5 状态机 DRAFT→PUBLISHED→DEPRECATED）：同一类型同一时刻仅一个 PUBLISHED 版本（应用层保证）。
CREATE TABLE system.dict_version (
    id               BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    dict_type_id     BIGINT        NOT NULL,                            -- 所属字典类型 ID
    version          INT           NOT NULL,                            -- 版本号（同类型内自增，1 起）
    status           VARCHAR(16)   NOT NULL,                            -- 状态机：DRAFT 草稿/PUBLISHED 已发布/DEPRECATED 已废弃
    effective_at     TIMESTAMPTZ   NULL,                                -- 生效时刻（P0 发布即生效 = now()；fy.delay 定时生效随 P1）
    published_at     TIMESTAMPTZ   NULL,                                -- 发布时刻（DRAFT 阶段为 NULL）
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted          SMALLINT      NOT NULL DEFAULT 0
);

-- 同一类型内版本号唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_dict_version_type_version ON system.dict_version (dict_type_id, version) WHERE deleted = 0;

-- 发布动作产生 UPDATE 生命周期（DRAFT→PUBLISHED、PUBLISHED→DEPRECATED），挂触发器
CREATE TRIGGER trg_dict_version_updated_at BEFORE UPDATE ON system.dict_version
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 字典条目表
-- ext_attrs JSONB：P0 API 不暴露该字段，插入走列默认值 '{}'（避免为此引入 JSONB TypeHandler，扩展属性读写随 P1 handler/ 交付）。
CREATE TABLE system.dict_item (
    id               BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    dict_version_id  BIGINT        NOT NULL,                            -- 所属字典版本 ID
    item_code        VARCHAR(64)   NOT NULL,                            -- 条目编码（同版本内唯一）
    item_name        VARCHAR(128)  NOT NULL,                            -- 条目名称
    parent_code      VARCHAR(64)   NULL,                                -- 父条目编码（NULL=顶层，层级字典树形表达）
    ext_attrs        JSONB         NOT NULL DEFAULT '{}',               -- 扩展属性（P0 不暴露，列默认值承载）
    sort             INT           NOT NULL DEFAULT 0,                  -- 排序号，小者在前
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted          SMALLINT      NOT NULL DEFAULT 0
);

-- 同一版本内条目编码唯一（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_dict_item_version_code ON system.dict_item (dict_version_id, item_code) WHERE deleted = 0;

-- 条目随草稿版本维护存在 UPDATE 生命周期（P1 条目编辑），挂触发器
CREATE TRIGGER trg_dict_item_updated_at BEFORE UPDATE ON system.dict_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
