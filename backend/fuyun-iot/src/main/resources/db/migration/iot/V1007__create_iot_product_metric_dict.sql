-- V1007：产品与物模型管理四表（FU-M14-02，P2 PR-2 Task 4）——iot_product / iot_product_command /
--   iot_metric_dict / iot_metric_mapping。
-- 号段说明：2026-09-26 迁移号勘误后 iot 增量走通用段（台账 docs/migrations/flyway-version-registry.md
--   V1007 行先记再改；宪法 A.4.1-3 禁改已应用迁移，故不经 V40x 续号）。
-- DDL 公共约定（V400 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend 宪法
--   A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）；唯一约束用部分唯一索引
--   WHERE deleted = 0。
-- 主键口径：iot_product.product_id / iot_metric_dict.metric_code 为外部标识/编码自然键（VARCHAR，
--   @TableId(INPUT)，V400 iot_device 同款偏离申报）；iot_product_command / iot_metric_mapping 用
--   雪花代理 id（行随管理台编辑全量替换重建，自然键不复用）。
-- 业务意图（14-iot.md FU-M14-02）：IoTDA 产品本地镜像 + 物模型 JSON 快照 + MDC 术语字典自管
--   （不经 M01）+ 属性→MDC 映射；失配期间遥测按未映射属性原样入库不静默丢弃（RAW_PASSTHROUGH）。

-- ---------------------------------------------------------------- 产品镜像表
CREATE TABLE iot.iot_product (
    product_id        VARCHAR(64)   PRIMARY KEY,                         -- IoTDA 产品标识（自然键，偏离申报见文件头）
    product_name      VARCHAR(128)  NOT NULL,                            -- 产品名称（管理台展示名）
    device_type       VARCHAR(32)   NOT NULL,                            -- 设备类型（总 Spec 5.1 矩阵 15 类）
    protocol_type     VARCHAR(32)   NOT NULL,                            -- 协议类型（MQTT/LwM2M/HTTPS/Modbus 等）
    data_format       VARCHAR(16)   NOT NULL,                            -- 数据格式：JSON/二进制
    manufacturer_name VARCHAR(128)  NULL,                                -- 厂商名称（可空）
    industry          VARCHAR(64)   NULL,                                -- 所属行业（可空）
    description       VARCHAR(255)  NULL,                                -- 产品描述（可空）
    model_definition  JSONB         NULL,                                -- 物模型 JSON 快照（services/properties 原文，产品未定义模型为空）
    sync_status       VARCHAR(16)   NOT NULL,                            -- 同步状态机：SYNCING 同步中/SYNCED 已同步/MISMATCH 失配
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 失配巡检查询路径（每日对账任务按 MISMATCH 圈定待对齐产品，14-iot FU-M14-02）
CREATE INDEX idx_iot_product_sync_status ON iot.iot_product (sync_status);

CREATE TRIGGER trg_iot_product_updated_at BEFORE UPDATE ON iot.iot_product
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 命令安全等级表
-- 命令白名单数据源（FU-M14-09 下发闸门按 product_id+command_name 查 allowed/safety_level）；
-- 管理台 PUT 全量替换：逻辑删旧行重建（历史留痕），部分唯一索引只约束未删行。
CREATE TABLE iot.iot_product_command (
    id             BIGINT        PRIMARY KEY,                             -- 雪花 ID（MP ASSIGN_ID）
    product_id     VARCHAR(64)   NOT NULL,                                -- IoTDA 产品标识（关联 iot_product 自然键）
    command_name   VARCHAR(128)  NOT NULL,                                -- 命令名称（物模型 commands[].name）
    service_id     VARCHAR(64)   NULL,                                    -- 所属服务 ID（物模型 service 维度，可空）
    safety_level   VARCHAR(16)   NOT NULL,                                -- 命令安全等级：SAFETY 安全级/TREATMENT 治疗级
    allowed        BOOLEAN       NOT NULL DEFAULT TRUE,                   -- 是否放行下发；落行默认按级别：SAFETY=true/TREATMENT=false（应用层写实际值，列默认仅兜底）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted        SMALLINT      NOT NULL DEFAULT 0
);

-- 同一产品同一命令至多一条未删标注（全量替换幂等的物理兜底，逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_iot_product_command ON iot.iot_product_command (product_id, command_name) WHERE deleted = 0;
-- 按产品圈定命令白名单（FU-M14-09 下发闸门前置查询路径）
CREATE INDEX idx_iot_product_command_product ON iot.iot_product_command (product_id);

CREATE TRIGGER trg_iot_product_command_updated_at BEFORE UPDATE ON iot.iot_product_command
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- MDC 指标术语字典表
-- 模块自管专业字典（不经 M01，14-iot.md §105）；跨品牌术语归一基准。
-- 硬删除口径：专业字典行无业务历史依赖（映射表按 metric_code 逻辑引用，删除前由应用层校验无引用），
--   故不设审计列与 deleted（唯一例外表，V400 公共约定按表申报豁免）；同理不挂 updated_at 触发器。
CREATE TABLE iot.iot_metric_dict (
    metric_code   VARCHAR(64)   PRIMARY KEY,                               -- MDC 编码（如 MDC_ECG_HEART_RATE，IEEE 11073 术语集）
    metric_name   VARCHAR(128)  NOT NULL,                                  -- 指标名称（中文，管理台展示）
    category      VARCHAR(16)   NOT NULL,                                  -- 指标类别：VITAL_SIGN 体征/WAVEFORM 波形/ALARM 报警/DEVICE_STATUS 设备状态
    data_type     VARCHAR(16)   NOT NULL,                                  -- 数据类型：NUMERIC 数值/TEXT 文本/JSON 结构化
    unit          VARCHAR(32)   NULL,                                      -- 计量单位（次每分/%/mmHg 等，可空）
    physio_min    NUMERIC       NULL,                                      -- 生理极限下界（超限即数据质量异常线索，可空）
    physio_max    NUMERIC       NULL,                                      -- 生理极限上界（同上，可空）
    default_level VARCHAR(16)   NULL                                       -- 默认告警级别：INFO 提示/WARNING 警告/CRITICAL 危急（与 iot_alarm_rule.level 同词表，可空）
);

-- ---------------------------------------------------------------- 属性 MDC 映射表
-- 物模型属性 → MDC 编码跨品牌归一（FU-M14-02）；失配策略默认 RAW_PASSTHROUGH（原文透传，14-iot.md
--   FU-M14-02"失配期间遥测按未映射属性原样入库不静默丢弃"）。
CREATE TABLE iot.iot_metric_mapping (
    id                BIGINT        PRIMARY KEY,                           -- 雪花 ID（MP ASSIGN_ID）
    product_id        VARCHAR(64)   NOT NULL,                              -- IoTDA 产品标识（关联 iot_product 自然键）
    property_name     VARCHAR(128)  NOT NULL,                              -- 物模型属性名（model_definition services[].properties[].name）
    metric_code       VARCHAR(64)   NOT NULL,                              -- MDC 编码（逻辑引用 iot_metric_dict，应用层校验存在性）
    mismatch_strategy VARCHAR(32)   NOT NULL DEFAULT 'RAW_PASSTHROUGH',    -- 失配策略：RAW_PASSTHROUGH 原文透传（当前唯一词表项，扩充随后续任务顺延）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 同一产品同一属性至多一条未删映射（跨品牌归一唯一性锚点，逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_iot_metric_mapping ON iot.iot_metric_mapping (product_id, property_name) WHERE deleted = 0;
-- 按字典编码反查引用面（字典硬删除前校验无引用路径）
CREATE INDEX idx_iot_metric_mapping_metric ON iot.iot_metric_mapping (metric_code);

CREATE TRIGGER trg_iot_metric_mapping_updated_at BEFORE UPDATE ON iot.iot_metric_mapping
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- MDC 字典种子（两行示例）
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V403 先例，版化迁移只跑一次，防人工重放重复插入）。
-- 审计口径：iot_metric_dict 无审计列（本文件头豁免申报）。
INSERT INTO iot.iot_metric_dict (metric_code, metric_name, category, data_type, unit, physio_min, physio_max, default_level)
SELECT 'MDC_ECG_HEART_RATE',
       '心率',
       'VITAL_SIGN',
       'NUMERIC',
       '次每分',
       20,
       300,
       'WARNING'
WHERE NOT EXISTS (SELECT 1 FROM iot.iot_metric_dict WHERE metric_code = 'MDC_ECG_HEART_RATE');

INSERT INTO iot.iot_metric_dict (metric_code, metric_name, category, data_type, unit, physio_min, physio_max, default_level)
SELECT 'MDC_PULSE_OXIM_SPO2',
       '血氧饱和度',
       'VITAL_SIGN',
       'NUMERIC',
       '%',
       50,
       100,
       'CRITICAL'
WHERE NOT EXISTS (SELECT 1 FROM iot.iot_metric_dict WHERE metric_code = 'MDC_PULSE_OXIM_SPO2');

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE iot.iot_product IS 'IoTDA 产品镜像表（FU-M14-02）：产品档案 + 物模型 JSON 快照 + 同步状态机';
COMMENT ON COLUMN iot.iot_product.product_id IS 'IoTDA 产品标识（自然键，注册中心分配后写入，禁止本地生成）';
COMMENT ON COLUMN iot.iot_product.product_name IS '产品名称（管理台展示名）';
COMMENT ON COLUMN iot.iot_product.device_type IS '设备类型（总 Spec 5.1 矩阵 15 类）';
COMMENT ON COLUMN iot.iot_product.protocol_type IS '协议类型：MQTT/LwM2M/HTTPS/Modbus 等';
COMMENT ON COLUMN iot.iot_product.data_format IS '数据格式：JSON/二进制';
COMMENT ON COLUMN iot.iot_product.manufacturer_name IS '厂商名称（可空）';
COMMENT ON COLUMN iot.iot_product.industry IS '所属行业（可空）';
COMMENT ON COLUMN iot.iot_product.description IS '产品描述（可空）';
COMMENT ON COLUMN iot.iot_product.model_definition IS '物模型 JSON 快照（IoTDA product_file 原文：services/properties/commands/events）';
COMMENT ON COLUMN iot.iot_product.sync_status IS '同步状态机：SYNCING 同步中（上架初态）/SYNCED 已同步/MISMATCH 失配（模型属性存在未映射项）';
COMMENT ON TABLE iot.iot_product_command IS '产品命令安全等级表（FU-M14-09 白名单数据源）：命令登记安全级/治疗级与放行状态';
COMMENT ON COLUMN iot.iot_product_command.product_id IS 'IoTDA 产品标识（关联 iot_product 自然键）';
COMMENT ON COLUMN iot.iot_product_command.command_name IS '命令名称（物模型 commands[].name）';
COMMENT ON COLUMN iot.iot_product_command.service_id IS '所属服务 ID（物模型 service 维度，可空）';
COMMENT ON COLUMN iot.iot_product_command.safety_level IS '命令安全等级：SAFETY 安全级/TREATMENT 治疗级';
COMMENT ON COLUMN iot.iot_product_command.allowed IS '是否放行下发；落行默认按级别：SAFETY=true/TREATMENT=false（应用层写实际值）';
COMMENT ON TABLE iot.iot_metric_dict IS 'MDC 指标术语字典（模块自管不经 M01）：跨品牌遥测术语归一基准；硬删除无审计列';
COMMENT ON COLUMN iot.iot_metric_dict.metric_code IS 'MDC 编码（IEEE 11073 术语，如 MDC_ECG_HEART_RATE）';
COMMENT ON COLUMN iot.iot_metric_dict.metric_name IS '指标名称（中文展示）';
COMMENT ON COLUMN iot.iot_metric_dict.category IS '指标类别：VITAL_SIGN 体征/WAVEFORM 波形/ALARM 报警/DEVICE_STATUS 设备状态';
COMMENT ON COLUMN iot.iot_metric_dict.data_type IS '数据类型：NUMERIC 数值/TEXT 文本/JSON 结构化';
COMMENT ON COLUMN iot.iot_metric_dict.unit IS '计量单位（次每分/%/mmHg 等，可空）';
COMMENT ON COLUMN iot.iot_metric_dict.physio_min IS '生理极限下界（超限即数据质量异常线索，可空）';
COMMENT ON COLUMN iot.iot_metric_dict.physio_max IS '生理极限上界（同上，可空）';
COMMENT ON COLUMN iot.iot_metric_dict.default_level IS '默认告警级别：INFO 提示/WARNING 警告/CRITICAL 危急（与 iot_alarm_rule.level 同词表，可空）';
COMMENT ON TABLE iot.iot_metric_mapping IS '物模型属性 MDC 映射表（FU-M14-02）：属性→编码跨品牌归一；失配默认原文透传';
COMMENT ON COLUMN iot.iot_metric_mapping.product_id IS 'IoTDA 产品标识（关联 iot_product 自然键）';
COMMENT ON COLUMN iot.iot_metric_mapping.property_name IS '物模型属性名（model_definition services[].properties[].name）';
COMMENT ON COLUMN iot.iot_metric_mapping.metric_code IS 'MDC 编码（逻辑引用 iot_metric_dict，应用层校验存在性）';
COMMENT ON COLUMN iot.iot_metric_mapping.mismatch_strategy IS '失配策略：RAW_PASSTHROUGH 原文透传（当前唯一词表项）';
