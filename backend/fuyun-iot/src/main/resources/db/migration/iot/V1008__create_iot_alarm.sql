-- V1008：告警引擎两表（FU-M14-08，P2 PR-2 Task 7）——iot_alarm_rule / iot_alarm。
-- 号段说明：iot 增量走通用段（V1007 勘误先例，台账 docs/migrations/flyway-version-registry.md
--   V1008 行先记再改；宪法 A.4.1-3 禁改已应用迁移）。
-- DDL 公共约定（V400/V1007 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend
--   宪法 A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）。
-- 主键口径：两表均用雪花代理 id（MP ASSIGN_ID，宪法 A.4.3-16）——规则无自然键（rule_name 可改），
--   告警行以 alarm_no 业务号对外（IotSeqGate.nextAlarmNo，AL{yyyyMMdd}{%05d}）。
-- 业务意图（14-iot.md FU-M14-08）：三类规则源（THRESHOLD 阈值/DEVICE_ALARM 设备告警透传/OFFLINE
--   离线）评估产生 iot_alarm 告警行；五项风暴抑制（同源聚合/抖动防护/离线抑制/风暴态/分级升级）
--   中的①同源聚合以部分唯一索引 uk_iot_alarm_active 作 DB 兜底——同设备同规则至多一条活跃告警行。

-- ---------------------------------------------------------------- 告警规则表
CREATE TABLE iot.iot_alarm_rule (
    id                  BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    rule_name           VARCHAR(128)  NOT NULL,                            -- 规则名称（管理台展示名）
    rule_type           VARCHAR(16)   NOT NULL,                            -- 规则类型：DEVICE_ALARM 设备告警透传/THRESHOLD 阈值/OFFLINE 离线
    device_id           VARCHAR(64)   NULL,                                -- 适用设备号（NULL=全部设备；指定则规则仅对该设备生效）
    metric_code         VARCHAR(64)   NULL,                                -- 指标编码（THRESHOLD/DEVICE_ALARM 必填，应用层校验；OFFLINE 规则无指标语义为空）
    compare_op          VARCHAR(2)    NULL,                                -- 阈值比较方向：> 高于 / < 低于（仅 THRESHOLD 规则有语义）
    threshold_value     NUMERIC       NULL,                                -- 阈值（THRESHOLD 必填，抖动防护②）
    duration_secs       INT           NULL,                                -- 持续时长秒（THRESHOLD 必填，抖动防护②：越限持续该时长方触发）
    recovery_band       NUMERIC       NULL,                                -- 恢复带（THRESHOLD 必填，抖动防护②：恢复带内不重复触发）
    silence_window_secs INT           NOT NULL DEFAULT 300,                -- 静默窗口秒（Spec 14-iot 规则列；P0 静默语义由①同源聚合 trigger_count 计数合并承载，列随 Spec 词表保留）
    offline_secs        INT           NULL,                                -- 离线判定秒（OFFLINE 必填：ONLINE 设备 last_online_at 距今超该值即告警）
    alarm_level         VARCHAR(16)   NOT NULL,                            -- 告警级别：INFO 提示/WARNING 警告/CRITICAL 危急
    escalate_after_secs INT           NOT NULL DEFAULT 300,                -- 升级时限秒（危急告警未确认超该值触发升级动作，抑制⑤；默认 300）
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,               -- 是否启用（禁用规则不参与评估）
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

-- 引擎评估扫描路径：按类型圈定启用规则（每次评估调起全量装载，规则量小无分页）
CREATE INDEX idx_iot_alarm_rule_type_enabled ON iot.iot_alarm_rule (rule_type, enabled) WHERE deleted = 0;

CREATE TRIGGER trg_iot_alarm_rule_updated_at BEFORE UPDATE ON iot.iot_alarm_rule
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 告警行表
CREATE TABLE iot.iot_alarm (
    id                BIGINT        PRIMARY KEY,                           -- 雪花 ID（MP ASSIGN_ID）
    alarm_no          VARCHAR(32)   NOT NULL,                              -- 告警业务号（AL{yyyyMMdd}{%05d}，IotSeqGate 签发，全局唯一）
    rule_id           BIGINT        NOT NULL,                              -- 命中规则 ID（关联 iot_alarm_rule，应用层保证存在）
    device_id         VARCHAR(64)   NOT NULL,                              -- 告警设备号
    patient_id        BIGINT        NULL,                                  -- 患者主索引（触发时绑定快照冗余，无绑定/公共设备为空）
    visit_id          VARCHAR(14)   NULL,                                  -- 住院就诊号（CF-3 I 型 14 位，触发时绑定快照冗余，可空）
    ward_id           BIGINT        NOT NULL,                              -- 病区 ID（触发时绑定快照或设备档案，WS 推送路由键）
    alarm_level       VARCHAR(16)   NOT NULL,                              -- 告警级别：INFO/WARNING/CRITICAL（触发时规则级别快照）
    metric_code       VARCHAR(64)   NOT NULL,                              -- 指标编码（离线告警为固定值 DEVICE_OFFLINE）
    trigger_value     VARCHAR(255)  NOT NULL,                              -- 触发值原文（保留原始形态文本，离线告警为最后在线时刻）
    status            VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',             -- 告警状态：ACTIVE 活跃/ACKNOWLEDGED 已确认/CLOSED 已关闭
    trigger_count     INT           NOT NULL DEFAULT 1,                    -- 累计触发次数（抑制①同源聚合计数：活跃期内重复触发仅本列+1）
    last_triggered_at TIMESTAMPTZ   NOT NULL DEFAULT now(),                -- 最近触发时刻（抑制①同触发时刷新）
    escalation_count  INT           NOT NULL DEFAULT 0,                    -- 已升级次数（抑制⑤防重发锚：CAS 限定旧值，仅首个升级方发布事件）
    last_escalated_at TIMESTAMPTZ   NULL,                                  -- 最近升级时刻（抑制⑤升级时限计算锚：空则回退 created_at）
    acknowledged_by   VARCHAR(64)   NULL,                                  -- 确认人（ACTIVE→ACKNOWLEDGED 写入）
    acknowledged_at   TIMESTAMPTZ   NULL,                                  -- 确认时刻
    closed_by         VARCHAR(64)   NULL,                                  -- 关闭人（人工关闭为操作者；自动恢复预留 system）
    closed_at         TIMESTAMPTZ   NULL,                                  -- 关闭时刻
    close_reason      VARCHAR(255)  NULL,                                  -- 关闭原因（关闭操作必填）
    trace_id          VARCHAR(64)   NULL,                                  -- 全链路追踪号（触发点 MDC 捕获，跨链路排查锚）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 告警号唯一（对外主标识，查询/确认/关闭端点按号定位）
CREATE UNIQUE INDEX uk_iot_alarm_no ON iot.iot_alarm (alarm_no);
-- 抑制①同源聚合 DB 兜底：同设备同规则至多一条活跃告警行（逻辑删与已关闭行不占用唯一性）——
--   并发新发窗口的物理防线，应用层 CAS incrementTriggerIfActive 为第一道
CREATE UNIQUE INDEX uk_iot_alarm_active ON iot.iot_alarm (rule_id, device_id) WHERE status = 'ACTIVE' AND deleted = 0;
-- 告警列表查询路径（GET /alarms 按病区+状态过滤分页）
CREATE INDEX idx_iot_alarm_ward_status ON iot.iot_alarm (ward_id, status);
-- 抑制⑤升级惰性扫描路径：ACTIVE+CRITICAL 候选圈定（评估调起扫描的准入索引）
CREATE INDEX idx_iot_alarm_escalation_scan ON iot.iot_alarm (status, alarm_level) WHERE deleted = 0;
-- 设备维度活跃告警查询路径（抑制③离线抑制：设备已有 ACTIVE 离线告警时跳过其衍生遥测告警）
CREATE INDEX idx_iot_alarm_device_active ON iot.iot_alarm (device_id) WHERE status = 'ACTIVE' AND deleted = 0;

CREATE TRIGGER trg_iot_alarm_updated_at BEFORE UPDATE ON iot.iot_alarm
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 规则种子（两行示例）
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V403/V1007 先例，防人工重放重复插入）。
-- 固定字面 id（雪花段外的种子保留段 90000x，V403 事件登记 id 同为字面量先例）。
INSERT INTO iot.iot_alarm_rule (id, rule_name, rule_type, device_id, metric_code, compare_op, threshold_value,
                                duration_secs, recovery_band, alarm_level)
SELECT 900001, '心率过速危急告警（示例）', 'THRESHOLD', NULL, 'MDC_ECG_HEART_RATE', '>', 150, 30, 10, 'CRITICAL'
WHERE NOT EXISTS (SELECT 1 FROM iot.iot_alarm_rule WHERE id = 900001);

INSERT INTO iot.iot_alarm_rule (id, rule_name, rule_type, device_id, metric_code, compare_op, threshold_value,
                                duration_secs, recovery_band, alarm_level)
SELECT 900002, '血氧过低危急告警（示例）', 'THRESHOLD', NULL, 'MDC_PULSE_OXIM_SPO2', '<', 90, 60, 10, 'CRITICAL'
WHERE NOT EXISTS (SELECT 1 FROM iot.iot_alarm_rule WHERE id = 900002);

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE iot.iot_alarm_rule IS 'IoT 告警规则表（FU-M14-08）：阈值/设备告警透传/离线三类规则源，告警引擎评估输入';
COMMENT ON COLUMN iot.iot_alarm_rule.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN iot.iot_alarm_rule.rule_name IS '规则名称（管理台展示名）';
COMMENT ON COLUMN iot.iot_alarm_rule.rule_type IS '规则类型：DEVICE_ALARM 设备告警透传（IoTDA device.alarm 帧命中）/THRESHOLD 阈值（遥测越限判定）/OFFLINE 离线（在线设备数据断流判定）';
COMMENT ON COLUMN iot.iot_alarm_rule.device_id IS '适用设备号（NULL=全部设备）';
COMMENT ON COLUMN iot.iot_alarm_rule.metric_code IS '指标编码（THRESHOLD/DEVICE_ALARM 必填；OFFLINE 规则无指标语义为空）';
COMMENT ON COLUMN iot.iot_alarm_rule.compare_op IS '阈值比较方向：> 高于 / < 低于（仅 THRESHOLD 规则有语义）';
COMMENT ON COLUMN iot.iot_alarm_rule.threshold_value IS '阈值（THRESHOLD 必填，抖动防护②拒保存缺失行）';
COMMENT ON COLUMN iot.iot_alarm_rule.duration_secs IS '持续时长秒（THRESHOLD 必填：越限持续该时长方触发，抖动防护②）';
COMMENT ON COLUMN iot.iot_alarm_rule.recovery_band IS '恢复带（THRESHOLD 必填：恢复带内不重复触发，抖动防护②）';
COMMENT ON COLUMN iot.iot_alarm_rule.silence_window_secs IS '静默窗口秒（默认 300；P0 静默语义由同源聚合 trigger_count 计数合并承载，列随 Spec 词表保留）';
COMMENT ON COLUMN iot.iot_alarm_rule.offline_secs IS '离线判定秒（OFFLINE 必填：ONLINE 设备 last_online_at 距今超该值即告警）';
COMMENT ON COLUMN iot.iot_alarm_rule.alarm_level IS '告警级别：INFO 提示/WARNING 警告/CRITICAL 危急';
COMMENT ON COLUMN iot.iot_alarm_rule.escalate_after_secs IS '升级时限秒（危急告警未确认超该值发布升级动作事件，抑制⑤；默认 300）';
COMMENT ON COLUMN iot.iot_alarm_rule.enabled IS '是否启用（禁用规则不参与评估）';
COMMENT ON TABLE iot.iot_alarm IS 'IoT 告警行表（FU-M14-08）：告警引擎产出与生命周期（活跃/确认/关闭）载体，绑定快照五元组冗余';
COMMENT ON COLUMN iot.iot_alarm.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN iot.iot_alarm.alarm_no IS '告警业务号（AL{yyyyMMdd}{%05d}，IotSeqGate 签发）';
COMMENT ON COLUMN iot.iot_alarm.rule_id IS '命中规则 ID（关联 iot_alarm_rule）';
COMMENT ON COLUMN iot.iot_alarm.device_id IS '告警设备号';
COMMENT ON COLUMN iot.iot_alarm.patient_id IS '患者主索引（触发时绑定快照冗余，可空）';
COMMENT ON COLUMN iot.iot_alarm.visit_id IS '住院就诊号（CF-3 I 型 14 位，触发时绑定快照冗余，可空）';
COMMENT ON COLUMN iot.iot_alarm.ward_id IS '病区 ID（绑定快照或设备档案，WS 推送路由键 /topic/iot/alarm/{wardId}）';
COMMENT ON COLUMN iot.iot_alarm.alarm_level IS '告警级别：INFO 提示/WARNING 警告/CRITICAL 危急（触发时规则级别快照）';
COMMENT ON COLUMN iot.iot_alarm.metric_code IS '指标编码（离线告警为固定值 DEVICE_OFFLINE）';
COMMENT ON COLUMN iot.iot_alarm.trigger_value IS '触发值原文（保留原始形态；离线告警为最后在线时刻文本）';
COMMENT ON COLUMN iot.iot_alarm.status IS '告警状态：ACTIVE 活跃/ACKNOWLEDGED 已确认/CLOSED 已关闭';
COMMENT ON COLUMN iot.iot_alarm.trigger_count IS '累计触发次数（抑制①同源聚合：活跃期内重复触发仅本列+1 不新发）';
COMMENT ON COLUMN iot.iot_alarm.last_triggered_at IS '最近触发时刻（同源聚合时刷新）';
COMMENT ON COLUMN iot.iot_alarm.escalation_count IS '已升级次数（抑制⑤防重发锚：CAS 限定旧值，仅首个升级方发布事件）';
COMMENT ON COLUMN iot.iot_alarm.last_escalated_at IS '最近升级时刻（升级时限计算锚，空则回退 created_at）';
COMMENT ON COLUMN iot.iot_alarm.acknowledged_by IS '确认人';
COMMENT ON COLUMN iot.iot_alarm.acknowledged_at IS '确认时刻';
COMMENT ON COLUMN iot.iot_alarm.closed_by IS '关闭人（人工关闭为操作者；自动恢复预留 system）';
COMMENT ON COLUMN iot.iot_alarm.closed_at IS '关闭时刻';
COMMENT ON COLUMN iot.iot_alarm.close_reason IS '关闭原因（关闭操作必填）';
COMMENT ON COLUMN iot.iot_alarm.trace_id IS '全链路追踪号（触发点 MDC 捕获）';
