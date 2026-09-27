-- V1101：冷链合规台账两表（FU-M16 冷链域，P2 PR-2 Task 12）——ward.cold_chain_archive / ward.cold_chain_record。
-- 号段说明：ward 专属固定百位段 V1100–V1199（台账 docs/migrations/flyway-version-registry.md
--   2026-09-26 行先记再改；宪法 A.4.1-3 禁改已应用迁移）。
-- DDL 公共约定（V400/V1008 先例）：审计列由数据库维护（DEFAULT now() + V1 公共触发器函数）；
--   deleted 逻辑删标记；不建外键约束（archive_no 关联完整性应用层保证——brief 标注 FK 语义即
--   关联键，物理外键禁建照 iot 侧形态）。
-- 主键口径：两表均用雪花代理 id（MP ASSIGN_ID）——档案行以 archive_no 业务号对外、记录行以
--   record_no 业务号对外（WardSeqGate 统一签发：ARCH{yyyyMMdd}{%05d} / CCR{yyyyMMdd}{%05d}）。
-- 巡检逾期判定：读时惰性（每日≥2 次、间隔≥6h——查询面 overdue 标记+注记，不落库；delay 队列
--   档位归 W-27 PR-4）。

-- ---------------------------------------------------------------- 冷链档案表
CREATE TABLE ward.cold_chain_archive (
    id               BIGINT        PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    archive_no       VARCHAR(32)   NOT NULL,                            -- 档案业务号（ARCH{yyyyMMdd}{%05d}，WardSeqGate 签发，全局唯一）
    purpose          VARCHAR(16)   NOT NULL,                            -- 用途：VACCINE 疫苗/BLOOD 血液/REAGENT 试剂/PHARMA 药品
    device_id        VARCHAR(64)   NOT NULL,                            -- 监测设备号（温度曲线经 IotTelemetryQueryPort 查询）
    temp_range_type  VARCHAR(16)   NOT NULL,                            -- 温度区间类型：FREEZE 冷冻[-25~-10]/COOL 冷藏[2~10]/SHELDED 遮光[0~20]/NORMAL 常温[10~30]
    verify_due_at    TIMESTAMPTZ   NULL,                                -- 校验/验证到期时刻（可空：未约定验证计划的档案为空）
    inventory_digest VARCHAR(500)  NULL,                                -- 存量清单摘要（可空：盘点留痕文本摘要）
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted          SMALLINT      NOT NULL DEFAULT 0
);

-- 档案号唯一（对外主标识，记录登记与查询端点按号定位）
CREATE UNIQUE INDEX uk_cold_chain_archive_no ON ward.cold_chain_archive (archive_no);
-- 档案列表查询路径（按用途过滤分页）
CREATE INDEX idx_cold_chain_archive_purpose ON ward.cold_chain_archive (purpose) WHERE deleted = 0;
-- 设备维度档案定位（温度曲线查询反查档案）
CREATE INDEX idx_cold_chain_archive_device ON ward.cold_chain_archive (device_id) WHERE deleted = 0;

CREATE TRIGGER trg_cold_chain_archive_updated_at BEFORE UPDATE ON ward.cold_chain_archive
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 冷链记录表
CREATE TABLE ward.cold_chain_record (
    id              BIGINT        PRIMARY KEY,                          -- 雪花 ID（MP ASSIGN_ID）
    record_no       VARCHAR(32)   NOT NULL,                             -- 记录业务号（CCR{yyyyMMdd}{%05d}，WardSeqGate 签发，全局唯一）
    archive_no      VARCHAR(32)   NOT NULL,                             -- 所属档案号（关联 cold_chain_archive.archive_no，应用层保证存在——不建物理外键）
    record_type     VARCHAR(16)   NOT NULL,                             -- 记录类型：INSPECTION 巡检/ALARM_HANDLE 告警处置/DEVIATION 偏差
    alarm_ref       VARCHAR(32)   NULL,                                 -- 关联告警号（ALARM_HANDLE 必填——应用层 WD-1005 校验，其余类型为空）
    second_operator VARCHAR(64)   NULL,                                 -- 双人核对第二人（ALARM_HANDLE 必填——应用层 WD-1005 校验，其余类型为空）
    content         JSONB         NULL,                                 -- 记录内容（JSON 载体：巡检读数/处置措施/偏差描述；轻量巡检可空）
    recorded_by     VARCHAR(64)   NOT NULL,                             -- 登记人（应用层写入操作者，系统动作为 system）
    recorded_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),               -- 登记时刻（巡检 overdue 判定窗口锚）
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted         SMALLINT      NOT NULL DEFAULT 0
);

-- 记录号唯一（对外主标识）
CREATE UNIQUE INDEX uk_cold_chain_record_no ON ward.cold_chain_record (record_no);
-- 档案维度记录列表与巡检 overdue 聚合查询路径（按档案+类型+时刻圈定）
CREATE INDEX idx_cold_chain_record_archive_type ON ward.cold_chain_record (archive_no, record_type, recorded_at);

CREATE TRIGGER trg_cold_chain_record_updated_at BEFORE UPDATE ON ward.cold_chain_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 列注释（枚举词表入列注释）
COMMENT ON TABLE ward.cold_chain_archive IS '冷链档案表（FU-M16 冷链域）：疫苗/血液/试剂/药品四类用途的冷链监测档案，温度曲线经 iot 遥测端口';
COMMENT ON COLUMN ward.cold_chain_archive.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN ward.cold_chain_archive.archive_no IS '档案业务号（ARCH{yyyyMMdd}{%05d}，WardSeqGate 签发）';
COMMENT ON COLUMN ward.cold_chain_archive.purpose IS '用途：VACCINE 疫苗/BLOOD 血液/REAGENT 试剂/PHARMA 药品';
COMMENT ON COLUMN ward.cold_chain_archive.device_id IS '监测设备号（温度曲线经 IotTelemetryQueryPort.series 查询）';
COMMENT ON COLUMN ward.cold_chain_archive.temp_range_type IS '温度区间类型：FREEZE 冷冻[-25~-10]℃/COOL 冷藏[2~10]℃/SHELDED 遮光[0~20]℃/NORMAL 常温[10~30]℃';
COMMENT ON COLUMN ward.cold_chain_archive.verify_due_at IS '校验/验证到期时刻（未约定验证计划为空）';
COMMENT ON COLUMN ward.cold_chain_archive.inventory_digest IS '存量清单摘要（盘点留痕文本，可空）';
COMMENT ON TABLE ward.cold_chain_record IS '冷链记录表（FU-M16 冷链域）：巡检/告警处置/偏差三类型台账，ALARM_HANDLE 双人核对+告警号回溯';
COMMENT ON COLUMN ward.cold_chain_record.id IS '雪花 ID（MP ASSIGN_ID）';
COMMENT ON COLUMN ward.cold_chain_record.record_no IS '记录业务号（CCR{yyyyMMdd}{%05d}，WardSeqGate 签发）';
COMMENT ON COLUMN ward.cold_chain_record.archive_no IS '所属档案号（关联 cold_chain_archive.archive_no，应用层保证存在）';
COMMENT ON COLUMN ward.cold_chain_record.record_type IS '记录类型：INSPECTION 巡检（每日≥2 次、间隔≥6h 合规基线）/ALARM_HANDLE 告警处置（登记完成发布 ward.cold-chain.alert-archived）/DEVIATION 偏差';
COMMENT ON COLUMN ward.cold_chain_record.alarm_ref IS '关联告警号（ALARM_HANDLE 必填，其余类型为空）';
COMMENT ON COLUMN ward.cold_chain_record.second_operator IS '双人核对第二人（ALARM_HANDLE 必填，其余类型为空）';
COMMENT ON COLUMN ward.cold_chain_record.content IS '记录内容（JSON 载体：巡检读数/处置措施/偏差描述，可空）';
COMMENT ON COLUMN ward.cold_chain_record.recorded_by IS '登记人（应用层写入操作者，系统动作为 system）';
COMMENT ON COLUMN ward.cold_chain_record.recorded_at IS '登记时刻（巡检 overdue 判定窗口锚）';
