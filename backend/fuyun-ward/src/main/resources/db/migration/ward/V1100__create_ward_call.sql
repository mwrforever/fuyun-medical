-- V1100：呼叫对讲状态机两表（FU-M16-01，P2 PR-2 Task 12）——ward.ward_call / ward.ward_call_routing_rule。
-- 号段说明：ward 专属固定百位段 V1100–V1199 首批（台账 docs/migrations/flyway-version-registry.md
--   2026-09-26 行先记再改；宪法 A.4.1-3 禁改已应用迁移）。全新 schema 享号段初始化豁免。
-- DDL 公共约定（V400/V1008 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数，backend
--   宪法 A.4.2-9）；deleted 逻辑删标记；不建外键约束（关联完整性应用层保证）。
-- 主键口径：两表均用雪花代理 id（MP ASSIGN_ID，宪法 A.4.3-16）——呼叫行以 call_no 业务号对外
--   （WardSeqGate.nextCallNo，CALL{yyyyMMdd}{%05d}）；路由规则无自然键（ward+类型+时段组合定位）。
-- 状态机（brief 冻结）：CREATED→ANSWERED→IN_PROGRESS（可选）→COMPLETED；侧支 CREATED/ANSWERED→
--   TRANSFERRED→ANSWERED；CREATED/TRANSFERRED→CANCELLED（终态 COMPLETED/CANCELLED 不再迁移）；
--   同床位新呼叫自动 CANCELLED 旧活跃呼叫（合并语义，应用层 CAS 批量置位）；COMPLETED 必填
--   result_summary（应用层 WD-1005 借承校验，DB 不加 CHECK——留系统动作路径弹性）；
--   升级=动作式（读时惰性判定照 inpatient 会诊先例：超 300s[常量默认] 由查询路径 CAS 递增
--   escalation_count，DB 字段防重发，状态不变仍可应答）。
-- 列面偏差申报：bed_id/patient_id 可空（brief 列面仅标 device_id 可空，但设备源事件契约
--   CallTriggeredPayload/AlarmTriggeredPayload 均无床位字段且患者可空——强制 NOT NULL 即消费落行
--   失败；手工创建入口应用层校验补齐）。

-- ---------------------------------------------------------------- 呼叫行表
CREATE TABLE ward.ward_call (
    id               BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    call_no          VARCHAR(32)   NOT NULL,                            -- 呼叫业务号（CALL{yyyyMMdd}{%05d}，WardSeqGate 签发，全局唯一）
    ward_id          BIGINT        NOT NULL,                            -- 病区 ID（列表过滤与 WS 推送路由锚）
    bed_id           BIGINT        NULL,                                -- 床位 ID（设备源/输液档无床位上下文为空；同床位合并取消锚）
    patient_id       BIGINT        NULL,                                -- 患者主索引（绑定快照冗余，无绑定/输液档为空）
    device_id        VARCHAR(64)   NULL,                                -- 设备号（IOT 源/输液档落值；手工创建为空）
    call_type        VARCHAR(16)   NOT NULL,                            -- 呼叫类型：NORMAL 普通/EMERGENCY 紧急/INFUSION 输液/SERVICE 服务
    source           VARCHAR(16)   NOT NULL,                            -- 呼叫来源：BEDSIDE 床头/BRROOM 卫生间/PATIENT_PAD 患者_pad/NURSE_PAD 护士_pad/IOT 设备
    status           VARCHAR(16)   NOT NULL DEFAULT 'CREATED',          -- 呼叫状态：CREATED/ANSWERED/IN_PROGRESS/COMPLETED/TRANSFERRED/CANCELLED
    escalation_count INT           NOT NULL DEFAULT 0,                  -- 已升级次数（升级动作式防重发锚：0=未升级，CAS 限定旧值仅首个判定方递增）
    processed_by     VARCHAR(64)   NULL,                                -- 处理人（IN_PROGRESS 写入；完成/转接沿用审计 updated_by 留痕）
    result_summary   VARCHAR(500)  NULL,                                -- 处理结果摘要（COMPLETED 必填——应用层 WD-1005 借承校验）
    source_ref       VARCHAR(64)   NULL,                                -- 来源引用（INFUSION 档=告警号；手工/设备源为空）
    answered_at      TIMESTAMPTZ   NULL,                                -- 应答时刻（CREATED→ANSWERED 写入）
    completed_at     TIMESTAMPTZ   NULL,                                -- 完成时刻（→COMPLETED 写入）
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),              -- 创建时刻（升级时限计算锚：超 300s 未升级由读路径递增）
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted          SMALLINT      NOT NULL DEFAULT 0
);

-- 呼叫号唯一（对外主标识，动作端点按号定位）
CREATE UNIQUE INDEX uk_ward_call_no ON ward.ward_call (call_no);
-- 同床位活跃呼叫圈定（合并取消与床位呼叫列表准入；逻辑删与终态行不占用）
CREATE INDEX idx_ward_call_bed_active ON ward.ward_call (bed_id)
    WHERE status IN ('CREATED', 'ANSWERED', 'IN_PROGRESS', 'TRANSFERRED') AND deleted = 0;
-- 呼叫列表查询路径（GET /ward-calls 按病区+状态过滤分页，读时惰性升级判定承载面）
CREATE INDEX idx_ward_call_ward_status ON ward.ward_call (ward_id, status);
-- 输液档同告警活跃行唯一（同源聚合 DB 兜底：同一告警号至多一条活跃 INFUSION 行——不同 eventId
-- 同告警号重发窗口的物理防线，iot_alarm uk_iot_alarm_active 同款形态）
CREATE UNIQUE INDEX uk_ward_call_infusion_active ON ward.ward_call (source_ref)
    WHERE call_type = 'INFUSION' AND status IN ('CREATED', 'ANSWERED', 'IN_PROGRESS', 'TRANSFERRED')
      AND source_ref IS NOT NULL AND deleted = 0;
-- 拔针复位圈定路径（nursing.infusion.completed 消费按患者定位活跃输液呼叫行——V800 id 63
-- 载荷为 executionNo/patientId/visitId/endedAt，无设备锚，patient_id 为唯一可用复位键）
CREATE INDEX idx_ward_call_patient_active ON ward.ward_call (patient_id)
    WHERE call_type = 'INFUSION' AND status IN ('CREATED', 'ANSWERED', 'IN_PROGRESS', 'TRANSFERRED')
      AND deleted = 0;

CREATE TRIGGER trg_ward_call_updated_at BEFORE UPDATE ON ward.ward_call
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 路由规则表
CREATE TABLE ward.ward_call_routing_rule (
    id                BIGINT        PRIMARY KEY,                        -- 雪花 ID（MP ASSIGN_ID）
    ward_id           BIGINT        NOT NULL,                           -- 病区 ID（规则三要素之一）
    call_type         VARCHAR(16)   NOT NULL,                           -- 呼叫类型（规则三要素之二）
    time_range        VARCHAR(11)   NOT NULL,                           -- 生效时段（规则三要素之三）：HHmm-HHmm 文本（如 0800-2000，闭开区间）
    target_chain      JSONB         NOT NULL,                           -- 目标链：JSON 字符串数组（按序转接目标，如 ["nurse-station-1","head-nurse"]）
    task_convert_flag BOOLEAN       NOT NULL DEFAULT FALSE,             -- 任务转换开关（EMERGENCY 类病区可开：转接触发任务创建——M05 任务创建为 PENDING，PR-3 闭合）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);

-- 规则三要素业务唯一（同病区同类型同时段至多一条生效规则；逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_ward_call_routing_rule ON ward.ward_call_routing_rule (ward_id, call_type, time_range)
    WHERE deleted = 0;

CREATE TRIGGER trg_ward_call_routing_rule_updated_at BEFORE UPDATE ON ward.ward_call_routing_rule
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE ward.ward_call IS '病房呼叫行表（FU-M16-01）：呼叫对讲状态机载体（六态迁移 + 动作式升级 + 合并取消）';
COMMENT ON COLUMN ward.ward_call.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN ward.ward_call.call_no IS '呼叫业务号（CALL{yyyyMMdd}{%05d}，WardSeqGate 签发）';
COMMENT ON COLUMN ward.ward_call.ward_id IS '病区 ID（列表过滤与推送路由锚）';
COMMENT ON COLUMN ward.ward_call.bed_id IS '床位 ID（设备源/输液档可空；同床位合并取消锚）';
COMMENT ON COLUMN ward.ward_call.patient_id IS '患者主索引（绑定快照冗余，可空）';
COMMENT ON COLUMN ward.ward_call.device_id IS '设备号（IOT 源/输液档落值，手工创建为空）';
COMMENT ON COLUMN ward.ward_call.call_type IS '呼叫类型：NORMAL 普通/EMERGENCY 紧急/INFUSION 输液（iot.alarm.triggered 落行）/SERVICE 服务';
COMMENT ON COLUMN ward.ward_call.source IS '呼叫来源：BEDSIDE 床头/BRROOM 卫生间/PATIENT_PAD 患者 pad/NURSE_PAD 护士 pad/IOT 设备';
COMMENT ON COLUMN ward.ward_call.status IS '呼叫状态：CREATED 已创建/ANSWERED 已应答/IN_PROGRESS 处理中/COMPLETED 已完成/TRANSFERRED 已转接/CANCELLED 已取消';
COMMENT ON COLUMN ward.ward_call.escalation_count IS '已升级次数（升级动作式防重发锚：读时惰性判定 CAS 限定 0 值，仅首个判定方递增，状态不变仍可应答）';
COMMENT ON COLUMN ward.ward_call.processed_by IS '处理人（IN_PROGRESS 写入）';
COMMENT ON COLUMN ward.ward_call.result_summary IS '处理结果摘要（COMPLETED 必填，应用层校验）';
COMMENT ON COLUMN ward.ward_call.source_ref IS '来源引用（INFUSION 档=告警号，其余为空）';
COMMENT ON COLUMN ward.ward_call.answered_at IS '应答时刻（CREATED/TRANSFERRED→ANSWERED 写入）';
COMMENT ON COLUMN ward.ward_call.completed_at IS '完成时刻（→COMPLETED 写入）';
COMMENT ON COLUMN ward.ward_call.created_at IS '创建时刻（升级时限计算锚：超 300s 未升级由读路径 CAS 递增）';
COMMENT ON TABLE ward.ward_call_routing_rule IS '呼叫路由规则表（FU-M16-01）：病区+类型+时段三维定位转接目标链，转接触发时解析';
COMMENT ON COLUMN ward.ward_call_routing_rule.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN ward.ward_call_routing_rule.ward_id IS '病区 ID（规则三要素之一）';
COMMENT ON COLUMN ward.ward_call_routing_rule.call_type IS '呼叫类型（规则三要素之二，词表同 ward_call.call_type）';
COMMENT ON COLUMN ward.ward_call_routing_rule.time_range IS '生效时段（规则三要素之三）：HHmm-HHmm 文本（如 0800-2000，起含终不含）';
COMMENT ON COLUMN ward.ward_call_routing_rule.target_chain IS '目标链（JSON 字符串数组，按序转接目标）';
COMMENT ON COLUMN ward.ward_call_routing_rule.task_convert_flag IS '任务转换开关（EMERGENCY 类病区可开：转接触发 M05 任务创建——PR-3 闭合，本 PR 落字段与判断）';
