-- V200：门诊号源池域三表（M03 Spec §5 方案 3.1「模板预生成池行汇总」选定——排班模板按放号规则
-- 展开生成排班日历（schedule）×号源池行（appt_number_pool 号别×时段粒度），池行持有总量/渠道配额/
-- 已用量/乐观锁版本；号源占用、释放、停诊、加号全部落在池行粒度；号源是全院唯一权威库存
-- （Spec §1 职责①，任何渠道占用与释放必须经本模块号源服务）。
-- 公共约定同 V602 形态（雪花 ID / 数据库维护审计列 / 逻辑删 / 无外键 / 部分唯一索引 / 状态列值域
-- = 枚举 code 且词表入列注释）；资金无涉红线（裁决 7）：三表零金额列——挂号费/退费金额归 M13 权威。

-- ---------------------------------------------------------------- 排班模板表
CREATE TABLE outpatient.schedule_template (
    id             BIGINT       PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    dept_code      VARCHAR(64)  NOT NULL,                        -- 开诊科室编码（M01 组织域科室 code）
    doctor_id      VARCHAR(64)  NOT NULL,                        -- 出诊医生 id（sys_employee，演示链路同 sys_user.id）
    eff_from       DATE         NOT NULL,                        -- 模板生效日（含当日）
    eff_to         DATE         NULL,                            -- 模板失效日（含当日）；NULL=长期有效
    week_pattern   VARCHAR(7)   NOT NULL,                        -- 每周出诊位串（7 位 0/1，位序周一~周日，如 1100000=周一/周二出诊）
    session        VARCHAR(16)  NOT NULL,                        -- 时段：MORNING 上午/AFTERNOON 下午/EVENING 晚间
    appt_type      VARCHAR(32)  NOT NULL,                        -- 号别词表=字典 outpatient.appt-type（V705 种子）：GENERAL 普通/EXPERT 专家/SPECIAL_DISEASE 专病/EMERGENCY 急诊/REVISIT 复诊
    slot_start     TIME         NOT NULL,                        -- 号段开始时刻
    slot_end       TIME         NOT NULL,                        -- 号段结束时刻（须晚于 slot_start，CHECK 兜底）
    slot_quota     INT          NOT NULL CHECK (slot_quota > 0), -- 该时段号总数（生成池行 total_quota 的母本）
    room           VARCHAR(64)  NULL,                            -- 诊疗室，可空
    release_days   INT          NOT NULL DEFAULT 7,              -- T+N 放号周期（天）
    release_time   TIME         NOT NULL DEFAULT '07:00',        -- 每日放号时点
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',       -- ACTIVE 启用/STOPPED 停用（停用模板不参与放号展开）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_schedule_template_week_pattern CHECK (week_pattern ~ '^[01]{7}$'),
    CONSTRAINT ck_schedule_template_slot_range CHECK (slot_start < slot_end),
    CONSTRAINT ck_schedule_template_eff_range CHECK (eff_to IS NULL OR eff_from <= eff_to)
);

-- 模板管理清单高频谓词（按科室筛模板）
CREATE INDEX idx_schedule_template_dept ON outpatient.schedule_template (dept_code) WHERE deleted = 0;

CREATE TRIGGER trg_schedule_template_updated_at BEFORE UPDATE ON outpatient.schedule_template
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 排班日历表
-- 由模板按放号规则批量生成（POST /schedules/generate，uk 幂等跳过），支持手工调整；停诊整日历生效。
CREATE TABLE outpatient.schedule (
    id             BIGINT       PRIMARY KEY,                      -- 雪花 ID（MP ASSIGN_ID）
    template_id    BIGINT       NOT NULL,                         -- 母本模板 id（schedule_template.id）
    sched_date     DATE         NOT NULL,                         -- 排班日期
    session        VARCHAR(16)  NOT NULL,                         -- MORNING 上午/AFTERNOON 下午/EVENING 晚间
    dept_code      VARCHAR(64)  NOT NULL,                         -- 开诊科室编码
    doctor_id      VARCHAR(64)  NOT NULL,                         -- 出诊医生 id
    appt_type      VARCHAR(32)  NOT NULL,                         -- 号别（词表同 schedule_template.appt_type）
    total_quota    INT          NOT NULL CHECK (total_quota > 0), -- 当日总号数（生成自 slot_quota；加号经池行 total_quota 增量）
    used_quota     INT          NOT NULL DEFAULT 0 CHECK (used_quota >= 0), -- 已用号数（池行 used_count 聚合展示口径）
    room           VARCHAR(64)  NULL,                             -- 诊疗室，可空
    status         VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',        -- NORMAL 正常/STOPPED 停诊（停诊整日历生效，池行联动 STOPPED）
    stop_reason    VARCHAR(255) NULL,                             -- 停诊原因（status=STOPPED 时应用层必填，发布 schedule.stopped 携带）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

-- 放号幂等锚：同模板同日同时段仅一行（逻辑删行不占用唯一性，POST /schedules/generate 捕获冲突跳过）
CREATE UNIQUE INDEX uk_schedule ON outpatient.schedule (template_id, sched_date, session) WHERE deleted = 0;

-- 余量查询与排班清单高频谓词（dept_code+sched_date，GET /number-pools/available 关联路径）
CREATE INDEX idx_schedule_dept_date ON outpatient.schedule (dept_code, sched_date) WHERE deleted = 0;

CREATE TRIGGER trg_schedule_updated_at BEFORE UPDATE ON outpatient.schedule
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 号源池表
-- 号源权威库存行（号别×时段粒度）：扣减/回补走 casOccupy/casRelease 双 CAS（version 乐观锁+余量
-- 谓词），Redis 池键 fy:outpatient:pool:{poolId} 为第一道闸、本表条件更新为第二道闸（Spec 3.2）。
CREATE TABLE outpatient.appt_number_pool (
    id             BIGINT       PRIMARY KEY,                      -- 雪花 ID（MP ASSIGN_ID）
    schedule_id    BIGINT       NOT NULL,                         -- 所属排班日历 id（schedule.id）
    appt_type      VARCHAR(32)  NOT NULL,                         -- 号别（词表同 schedule_template.appt_type）
    slot_start     TIME         NOT NULL,                         -- 号段开始时刻（须早于 slot_end，CHECK 兜底）
    slot_end       TIME         NOT NULL,                         -- 号段结束时刻
    total_quota    INT          NOT NULL CHECK (total_quota > 0), -- 号总数（加号授权经 casAddExtraQuota 增量，加号占用计数 extra_used）
    channel_quota  VARCHAR(255) NOT NULL DEFAULT '{"PORTAL":60,"WINDOW":30,"KIOSK":5,"RESERVED":5}', -- 线上/窗口/自助/预留 JSON 配额百分比（P1 校验仅 PORTAL/WINDOW 通道计数）
    used_count     INT          NOT NULL DEFAULT 0 CHECK (used_count >= 0), -- 已用号数（CAS 余量谓词 used_count < total_quota 的左操作数）
    extra_used     INT          NOT NULL DEFAULT 0,               -- 加号已用数（加号授权走 total_quota 增量，本列计加号占用，Task 5 挂号消费）
    version        INT          NOT NULL DEFAULT 0,               -- 乐观锁版本（casOccupy/casRelease/casAddExtraQuota 单调递增，注解 SQL 显式谓词）
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',        -- ACTIVE 可约/STOPPED 停用（停诊联动）/EXPIRED 过期（对账归档）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT ck_appt_number_pool_slot_range CHECK (slot_start < slot_end)
);

-- 池行唯一锚：同排班同号别同号段仅一行（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_pool ON outpatient.appt_number_pool (schedule_id, appt_type, slot_start) WHERE deleted = 0;

-- 停诊整池批处理与预约回池路径（按 schedule_id 定位池行集合）
CREATE INDEX idx_appt_number_pool_schedule ON outpatient.appt_number_pool (schedule_id) WHERE deleted = 0;

CREATE TRIGGER trg_appt_number_pool_updated_at BEFORE UPDATE ON outpatient.appt_number_pool
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
