-- V1010：联动规则两表（FU-M14-10，P2 PR-2 Task 9）——linkage_rule / iot_linkage_log。
-- 号段说明：iot 增量走通用段（V1009 先例，台账 docs/migrations/flyway-version-registry.md
--   V1010 行已先记；宪法 A.4.1-3 禁改已应用迁移）。
-- DDL 公共约定（V400/V1008 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend
--   宪法 A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）。
-- 主键口径：两表均用雪花代理 id（MP ASSIGN_ID，宪法 A.4.3-16）——规则无自然键（rule_name 可改），
--   联动行以 linkage_no 业务号对外（IotSeqGate.nextLinkageNo，LG{yyyyMMdd}{%05d}）。
-- 业务意图（14-iot.md FU-M14-10 / 90-cross-review B-5 修订语义）：触发-条件-动作三段式——触发源
--   （ALARM_TRIGGERED 告警触发/TELEMETRY_ANOMALY 遥测异常/DEVICE_STATUS 设备状态变更）事件命中
--   trigger_condition 条件即执行 action_type 动作，逐次执行落 iot_linkage_log 留痕并发布
--   iot.linkage.executed；联动与告警规则解耦（同一告警可挂多条联动，告警侧零感知）。
-- 预置模板两条（输液告急场景，B-5 收窄语义——不做「创建护理任务」防与 M05「告警升级挂执行单、
--   不新建任务」契约双路径任务风暴）：①PDA 强提醒（NOTIFY，默认启用；P2 降级面=WS 推送+留痕，
--   GC17①）②病区播报呼叫（CALL_TRANSFER，默认关——M16-01 病区级配置向导语义，病区按需开启）。

-- ---------------------------------------------------------------- 联动规则表
CREATE TABLE iot.linkage_rule (
    id                BIGINT       PRIMARY KEY,                  -- 雪花 ID（MP ASSIGN_ID）
    rule_name         VARCHAR(128) NOT NULL,                     -- 规则名称（管理台展示名）
    trigger_source    VARCHAR(32)  NOT NULL,                     -- 触发来源：ALARM_TRIGGERED 告警触发/TELEMETRY_ANOMALY 遥测异常/DEVICE_STATUS 设备状态变更
    trigger_condition JSONB        NOT NULL,                     -- 触发条件（JSONB 键值等值匹配：alarm_type 告警类型/metric_code 指标编码/device_type 设备类型；空对象=全部命中；未知键服务层拒保存）
    action_type       VARCHAR(32)  NOT NULL,                     -- 动作类型：NOTIFY WS 告警强提醒/M01_NOTIFY 经 M01 通知中心/CALL_TRANSFER 转发 M16 呼叫/NURSING_TASK 创建 M05 护理任务/WARD_BROADCAST M16 病区播报
    action_config     JSONB        NULL,                         -- 动作配置快照（JSONB，动作参数透传，可空）
    target_ward_id    BIGINT       NULL,                         -- 目标病区 ID（可空：空=跟随触发源病区路由）
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,        -- 是否启用（禁用规则不参与触发匹配）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);

-- 执行器匹配扫描路径：按触发源圈定启用规则（每事件调起全量装载，规则量小无分页）
CREATE INDEX idx_linkage_rule_source_enabled ON iot.linkage_rule (trigger_source, enabled) WHERE deleted = 0;

CREATE TRIGGER trg_linkage_rule_updated_at BEFORE UPDATE ON iot.linkage_rule
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 联动执行日志表
CREATE TABLE iot.iot_linkage_log (
    id             BIGINT       PRIMARY KEY,                    -- 雪花 ID（MP ASSIGN_ID）
    linkage_no     VARCHAR(32)  NOT NULL,                       -- 联动执行业务号（LG{yyyyMMdd}{%05d}，IotSeqGate 签发，全局唯一）
    rule_id        BIGINT       NOT NULL,                       -- 命中联动规则 ID（关联 linkage_rule，应用层保证存在）
    trigger_source VARCHAR(32)  NOT NULL,                       -- 触发来源（命中时快照）：ALARM_TRIGGERED/TELEMETRY_ANOMALY/DEVICE_STATUS
    trigger_ref    VARCHAR(64)  NOT NULL,                       -- 触发来源引用（告警号 AL... 等业务号，回溯锚）
    action_type    VARCHAR(32)  NOT NULL,                       -- 动作类型（命中时快照）：NOTIFY/M01_NOTIFY/CALL_TRANSFER/NURSING_TASK/WARD_BROADCAST
    action_result  VARCHAR(16)  NOT NULL,                       -- 动作执行结果：SUCCESS 成功/FAILED 失败（重试耗尽终态）/PENDING 暂存（目标域未上线，回接方收口）
    retry_count    INT          NOT NULL DEFAULT 0,             -- 累计重试次数（自动重试+人工重推累计；0=首试即成）
    error_msg      VARCHAR(500) NULL,                           -- 失败原因/暂存注记（SUCCESS 为空；PENDING 行承载 WardUnavailable 等域缺位注记）
    executed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),         -- 执行时刻（动作分派/终态判定时点；PENDING 行为暂存时点，回接完成时点以 updated_at 承载）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

-- 联动号唯一（对外主标识，查询/人工重推端点按号定位）
CREATE UNIQUE INDEX uk_iot_linkage_no ON iot.iot_linkage_log (linkage_no);
-- 联动日志查询路径（GET /linkage-logs 按规则+结果过滤分页）
CREATE INDEX idx_iot_linkage_log_rule_result ON iot.iot_linkage_log (rule_id, action_result);
-- FAILED 人工重推清单定位路径（管理台失败行扫描，部分索引准入）
CREATE INDEX idx_iot_linkage_log_failed ON iot.iot_linkage_log (action_result, executed_at)
    WHERE action_result = 'FAILED' AND deleted = 0;

CREATE TRIGGER trg_iot_linkage_log_updated_at BEFORE UPDATE ON iot.iot_linkage_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 预置模板种子（输液告急场景两条）
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V1008 先例，防人工重放重复插入）。
-- 固定字面 id（雪花段外的种子保留段 90001x，V403 事件登记/V1008 规则种子同为字面量先例）。
INSERT INTO iot.linkage_rule (id, rule_name, trigger_source, trigger_condition, action_type, enabled)
SELECT 900011, '输液告急→PDA 强提醒', 'ALARM_TRIGGERED', '{"alarm_type": "INFUSION_SHORTAGE"}', 'NOTIFY', TRUE
WHERE NOT EXISTS (SELECT 1 FROM iot.linkage_rule WHERE id = 900011);

INSERT INTO iot.linkage_rule (id, rule_name, trigger_source, trigger_condition, action_type, enabled)
SELECT 900012, '输液告急→病区播报呼叫', 'ALARM_TRIGGERED', '{"alarm_type": "INFUSION_SHORTAGE"}', 'CALL_TRANSFER', FALSE
WHERE NOT EXISTS (SELECT 1 FROM iot.linkage_rule WHERE id = 900012);

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE iot.linkage_rule IS 'IoT 联动规则表（FU-M14-10）：触发-条件-动作三段式配置，联动执行器匹配输入';
COMMENT ON COLUMN iot.linkage_rule.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN iot.linkage_rule.rule_name IS '规则名称（管理台展示名）';
COMMENT ON COLUMN iot.linkage_rule.trigger_source IS '触发来源：ALARM_TRIGGERED 告警触发（iot.alarm.triggered 事件）/TELEMETRY_ANOMALY 遥测异常（iot.telemetry.anomaly）/DEVICE_STATUS 设备状态变更（iot.device.status-changed）';
COMMENT ON COLUMN iot.linkage_rule.trigger_condition IS '触发条件（JSONB 键值等值匹配：alarm_type 告警类型/metric_code 指标编码/device_type 设备类型；空对象=全部命中；键词表外服务层拒保存）';
COMMENT ON COLUMN iot.linkage_rule.action_type IS '动作类型：NOTIFY WS 告警强提醒（告警主题重复强化）/M01_NOTIFY 经 M01 通知中心发通知/CALL_TRANSFER 转发 M16 呼叫/NURSING_TASK 创建 M05 护理任务/WARD_BROADCAST M16 病区播报';
COMMENT ON COLUMN iot.linkage_rule.action_config IS '动作配置快照（JSONB，动作参数透传，可空）';
COMMENT ON COLUMN iot.linkage_rule.target_ward_id IS '目标病区 ID（可空：空=跟随触发源病区路由）';
COMMENT ON COLUMN iot.linkage_rule.enabled IS '是否启用（禁用规则不参与触发匹配）';
COMMENT ON TABLE iot.iot_linkage_log IS 'IoT 联动执行日志表（FU-M14-10）：逐次动作执行留痕与失败重推载体，执行结果经 iot.linkage.executed 供审计订阅';
COMMENT ON COLUMN iot.iot_linkage_log.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN iot.iot_linkage_log.linkage_no IS '联动执行业务号（LG{yyyyMMdd}{%05d}，IotSeqGate 签发）';
COMMENT ON COLUMN iot.iot_linkage_log.rule_id IS '命中联动规则 ID（关联 linkage_rule）';
COMMENT ON COLUMN iot.iot_linkage_log.trigger_source IS '触发来源（命中时规则词表快照）：ALARM_TRIGGERED/TELEMETRY_ANOMALY/DEVICE_STATUS';
COMMENT ON COLUMN iot.iot_linkage_log.trigger_ref IS '触发来源引用（告警号 AL... 等业务号，回溯锚）';
COMMENT ON COLUMN iot.iot_linkage_log.action_type IS '动作类型（命中时规则词表快照）：NOTIFY/M01_NOTIFY/CALL_TRANSFER/NURSING_TASK/WARD_BROADCAST';
COMMENT ON COLUMN iot.iot_linkage_log.action_result IS '动作执行结果：SUCCESS 成功/FAILED 失败（自动重试耗尽终态，可人工重推）/PENDING 暂存（目标业务域未上线，回接方收口）';
COMMENT ON COLUMN iot.iot_linkage_log.retry_count IS '累计重试次数（自动重试+人工重推累计；0=首试即成）';
COMMENT ON COLUMN iot.iot_linkage_log.error_msg IS '失败原因/暂存注记（SUCCESS 为空；PENDING 行承载 WardUnavailable/NursingUnavailable 等域缺位注记）';
COMMENT ON COLUMN iot.iot_linkage_log.executed_at IS '执行时刻（动作分派/终态判定时点；PENDING 行为暂存时点，回接完成时点以 updated_at 承载）';
