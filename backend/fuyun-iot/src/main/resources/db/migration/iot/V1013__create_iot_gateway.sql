-- V1013：边缘网关表（FU-M14-12，P2 PR-2 Task 11）——iot_gateway 单表。
-- 号段说明：iot 增量走通用段（台账 docs/migrations/flyway-version-registry.md V1013 行已排定，
--   P2 PR-2 Task 11 落盘；宪法 A.4.1-3 禁改已应用迁移）。
-- DDL 公共约定（V1010 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend
--   宪法 A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）。
-- 主键口径：gateway_id 为 IoTDA 网关设备标识自然键（iot_device.gateway_id 引用列同形 VARCHAR(64)，
--   V400 先例），@TableId(INPUT) 直写不本地生成——宪法 A.4.3-16 的 ASSIGN_ID 约束针对代理主键，
--   自然键实体不适用（IotDeviceEntity 同款偏离声明）。
-- 业务意图（14-iot.md 领域模型 FU-M14-12 / brief 冻结面）：模式 B/C 网关本地档案——网关经 IoTDA
--   注册（Registry 直通），拓扑（子设备挂载）经 IoTDA 网关-子设备关系维护，本地只做 CRUD 档案与
--   联动展示（子设备数不落列，经 iot_device.gateway_id 计数直通展示，P1 FU-M14-12 完整化；
--   OTA 软件版本列随 P1 落地）。standby_of 热备对端落实总 Spec 5.2「双网关热备」：指向存在网关、
--   禁自引用/成环（服务层校验，IOT-1025）。

CREATE TABLE iot.iot_gateway (
    gateway_id     VARCHAR(64)  PRIMARY KEY,                  -- 网关标识（IoTDA 网关设备标识自然键，iot_device.gateway_id 引用同形）
    gateway_name   VARCHAR(128) NOT NULL,                     -- 网关名称（管理台展示名）
    mode           VARCHAR(8)   NOT NULL,                     -- 接入模式：B 串口服务器/C 边缘适配器（总 Spec 5.2 模式 B/C 网关）
    standby_of     VARCHAR(64)  NULL,                         -- 热备对端网关标识（可空：无双机热备场景；须指向存在网关，禁自引用/成环）
    ward_id        BIGINT       NOT NULL,                     -- 归属病区 ID（网关服务病区，管理台按病区过滤主路径）
    status         VARCHAR(16)  NOT NULL,                     -- 网关状态：ONLINE 在线/OFFLINE 离线/MAINTENANCE 维护
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

-- 管理台分页过滤主路径（病区维度圈定网关清单，部分索引准入逻辑删行）
CREATE INDEX idx_iot_gateway_ward ON iot.iot_gateway (ward_id) WHERE deleted = 0;
-- 热备引用反查路径（删除守卫：有其他网关以本网关为热备对端时拒删，防悬挂引用）
CREATE INDEX idx_iot_gateway_standby ON iot.iot_gateway (standby_of) WHERE deleted = 0 AND standby_of IS NOT NULL;

CREATE TRIGGER trg_iot_gateway_updated_at BEFORE UPDATE ON iot.iot_gateway
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE iot.iot_gateway IS 'IoT 边缘网关档案（FU-M14-12）：模式 B/C 网关本地 CRUD 面；注册与拓扑经 IoTDA 维护（Registry 直通），本地仅档案与联动展示';
COMMENT ON COLUMN iot.iot_gateway.gateway_id IS '网关标识（IoTDA 网关设备标识自然键，iot_device.gateway_id 引用同形）';
COMMENT ON COLUMN iot.iot_gateway.gateway_name IS '网关名称（管理台展示名）';
COMMENT ON COLUMN iot.iot_gateway.mode IS '接入模式：B 串口服务器（RS232 设备经串口服务器汇聚）/C 边缘适配器（BLE/RFID 等短距设备经边缘网关汇聚）';
COMMENT ON COLUMN iot.iot_gateway.standby_of IS '热备对端网关标识（可空：无双机热备场景；总 Spec 5.2 双网关热备——服务层校验须指向存在网关且禁自引用/成环）';
COMMENT ON COLUMN iot.iot_gateway.ward_id IS '归属病区 ID（网关服务病区，管理台按病区过滤主路径）';
COMMENT ON COLUMN iot.iot_gateway.status IS '网关状态：ONLINE 在线/OFFLINE 离线/MAINTENANCE 维护';
