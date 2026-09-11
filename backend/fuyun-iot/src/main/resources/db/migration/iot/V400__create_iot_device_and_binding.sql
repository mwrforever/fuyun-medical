-- V400：IoT 设备档案表 + 设备患者绑定表（M14 Spec §4 领域模型，BRIEF-PR4-01 §2）。
-- 号段登记（BRIEF-PR4-01 §1.1 / CHANGELOG 2026-09-10 条目）：iot 域占用 V400–V499，本批用 V400–V403。
-- DDL 公共约定（V300 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend 宪法 A.4.2-9）；
--   deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）；唯一约束用部分唯一索引 WHERE deleted = 0。
-- 主键偏离声明（BRIEF-PR4-01 §1.2）：iot_device 主键 = device_id VARCHAR(64)（IoTDA 设备标识自然键），
--   不用雪花代理 id——设备身份由外部系统（IoTDA）分配，本地代理 id 无消费方（P0 无设备管理 API），
--   且遥测/绑定/事件 payload 全部以 device_id 关联；宪法 A.4.3-16 的 ASSIGN_ID 约束针对代理主键实体，
--   自然键实体不适用（实体侧对应 @TableId(type=INPUT)，随 B4.2 落码申报）。
-- 敏感红线（14-iot §9）：credential_ref 仅存一机一密凭证引用，密钥明文禁入此表及任何表。

-- ---------------------------------------------------------------- 设备档案表
CREATE TABLE iot.iot_device (
    device_id       VARCHAR(64)   PRIMARY KEY,                          -- IoTDA 设备标识（自然键，偏离声明见文件头）
    node_id         VARCHAR(64)   NULL,                                 -- 设备侧标识（物模型节点，可空）
    product_id      VARCHAR(64)   NULL,                                 -- IoTDA 产品标识（产品镜像表随 P1，先落引用列避 ALTER）
    device_name     VARCHAR(128)  NOT NULL,                             -- 设备名称
    device_type     VARCHAR(32)   NOT NULL,                             -- 设备类型（总 Spec 5.1 矩阵 15 类）
    access_mode     VARCHAR(16)   NOT NULL,                             -- 接入模式：A 直连/B 串口服务器/C 边缘适配器/D HL7 引擎
    gateway_id      VARCHAR(64)   NULL,                                 -- 所属网关（直连设备为空）
    ward_id         BIGINT        NULL,                                 -- 归属病区 ID（可空：未部署设备）
    bed_id          BIGINT        NULL,                                 -- 固定安装设备当前位置（移动式设备为空）
    asset_ref       VARCHAR(64)   NULL,                                 -- M15 资产号引用（展示级冗余，权威在 asset 域）
    credential_ref  VARCHAR(128)  NULL,                                 -- 一机一密凭证引用（密钥明文禁入库，14-iot 红线）
    status          VARCHAR(16)   NOT NULL,                             -- 状态机：INACTIVE/ONLINE/OFFLINE/ABNORMAL/DISABLED（14-iot §5）
    last_online_at  TIMESTAMPTZ   NULL,                                 -- 最近上线时刻（IoTDA 设备状态数据源驱动）
    last_offline_at TIMESTAMPTZ   NULL,                                 -- 最近离线时刻（IoTDA 设备状态数据源驱动）
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 按病区圈定设备清单（病区视图/大屏按床位设备状态墙查询路径）
CREATE INDEX idx_iot_device_ward_id ON iot.iot_device (ward_id);
-- 按状态筛选设备（离线告警巡检/在线率统计查询路径）
CREATE INDEX idx_iot_device_status ON iot.iot_device (status);

-- updated_at 触发器：复用 integration V1 公共函数（跨 schema 复用，全项目禁重复定义）
CREATE TRIGGER trg_iot_device_updated_at BEFORE UPDATE ON iot.iot_device
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 设备患者绑定表
-- 绑定历史只增：UNBOUND 行不物理删（数据归属回溯依据，14-iot §5），解绑走状态迁移 UPDATE；
-- 新绑定必须由 BOUND 之外状态新建记录，禁止复用历史记录（部分唯一索引兜底"同一设备同一时刻至多一条绑定中"）。
CREATE TABLE iot.iot_binding (
    id             BIGINT        PRIMARY KEY,                            -- 雪花 ID（MP ASSIGN_ID）
    device_id      VARCHAR(64)   NOT NULL,                               -- IoTDA 设备标识（关联 iot_device 自然键）
    patient_id     BIGINT        NOT NULL,                               -- 患者 ID（M02，绑定快照五元组）
    visit_id       BIGINT        NOT NULL,                               -- 就诊 ID（M04，绑定快照五元组）
    bed_id         BIGINT        NULL,                                   -- 床位 ID（固定式绑定落，移动式可空）
    ward_id        BIGINT        NOT NULL,                               -- 病区 ID（绑定快照五元组）
    bind_type      VARCHAR(16)   NOT NULL,                               -- 绑定模式：FIXED 固定式/MOBILE 移动式
    status         VARCHAR(16)   NOT NULL,                               -- 状态机：BOUND 绑定中/UNBINDING 解绑中/UNBOUND 已解绑（14-iot §5）
    bind_reason    VARCHAR(255)  NULL,                                   -- 绑定原因（可空）
    unbind_reason  VARCHAR(255)  NULL,                                   -- 解绑原因：转床/消毒/维修/出院/调拨
    bound_by       VARCHAR(64)   NOT NULL DEFAULT 'system',              -- 绑定操作人（种子/系统动作为 system）
    bound_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),                 -- 绑定生效时刻
    unbound_at     TIMESTAMPTZ   NULL,                                   -- 解绑完成时刻（未解绑为空）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted        SMALLINT      NOT NULL DEFAULT 0
);

-- 同一设备同一时刻至多一条"绑定中"（逻辑删行不占用唯一性，14-iot §4 领域约束）
CREATE UNIQUE INDEX uk_iot_binding_device_bound ON iot.iot_binding (device_id) WHERE status = 'BOUND' AND deleted = 0;
-- 按患者回溯其使用过的设备集合（时序查询患者维度展开路径，14-iot §3.3）
CREATE INDEX idx_iot_binding_patient_id ON iot.iot_binding (patient_id);
-- 按床位查询当前固定式绑定（病区视图/床位设备状态墙查询路径）
CREATE INDEX idx_iot_binding_bed_id ON iot.iot_binding (bed_id);

CREATE TRIGGER trg_iot_binding_updated_at BEFORE UPDATE ON iot.iot_binding
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
