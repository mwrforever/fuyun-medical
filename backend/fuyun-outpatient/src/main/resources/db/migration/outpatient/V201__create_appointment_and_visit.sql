-- V201：门诊预约挂号与就诊登记四表（M03 Spec §4/§5，Task 5）：appointment（全渠道统一预约/挂号单，
-- appt_no=AP+yyyyMMdd+6 位流水业务号）、visit（就诊记录，visit_id=O+yyyyMMdd+5 位流水，CF-3 冻结
-- 结构、M03 唯一签发主体）、appt_credit_record（爽约信用记录，限约拦截依据）、visit_status_log
-- （visit 迁移日志，03 Spec 红线 5「每迁必记」，只增表）。
-- 公共约定同 V200 形态（雪花 ID / 数据库维护审计列 / 逻辑删 / 无外键 / 部分唯一索引 / 状态列值域
-- = 枚举 code 且词表入列注释）；资金无涉红线（裁决 7）：零金额列——fee_status/fee_settlement_id 仅
-- 存结算状态与结算单回填锚，挂号费/退费金额一律 M13 权威。

-- ---------------------------------------------------------------- 预约/挂号单表
-- 全渠道统一预约单：PORTAL 渠道 RESERVED 占位（pay_deadline 支付时限），WINDOW/KIOSK 一步直达 TAKEN；
-- 取号经 casTake 回填 visit_id；dept_code 为经 schedule join 的冗余列（uk_appt_patient 限购谓词落位）。
CREATE TABLE outpatient.appointment (
    id                BIGINT       PRIMARY KEY,                       -- 雪花 ID（MP ASSIGN_ID）
    appt_no           VARCHAR(32)  NOT NULL,                          -- 预约单业务号（AP+yyyyMMdd+6 位流水，uk_appt_no）
    patient_id        BIGINT       NOT NULL,                          -- 患者主索引（解析归一后主档，M02 红线 1）
    schedule_id       BIGINT       NOT NULL,                          -- 排班日历 id（schedule.id）
    pool_id           BIGINT       NOT NULL,                          -- 号源池行 id（appt_number_pool.id，回池定位锚）
    dept_code         VARCHAR(64)  NOT NULL,                          -- 开诊科室编码（冗余列，限购唯一谓词维度）
    appt_type         VARCHAR(32)  NOT NULL,                          -- 号别（词表同 appt_number_pool.appt_type）
    sched_date        DATE         NOT NULL,                          -- 排班日期（限购唯一谓词维度）
    slot_start        TIME         NOT NULL,                          -- 号段开始时刻
    slot_end          TIME         NOT NULL,                          -- 号段结束时刻
    channel           VARCHAR(16)  NOT NULL,                          -- 预约渠道：WINDOW 窗口/KIOSK 自助机/PORTAL 公众号/MINIAPP 小程序/CONSULT 诊间/EXTERNAL 外联
    fee_status        VARCHAR(16)  NOT NULL DEFAULT 'UNPAID',         -- 挂号费状态：UNPAID 未缴/PAID 已缴/REFUNDED 已退（金额归 M13 权威）
    fee_settlement_id BIGINT       NULL,                              -- 挂号费结算单 id（回填，退号退费定位锚）
    pay_deadline      TIMESTAMPTZ  NULL,                              -- 支付时限（PORTAL 占位写 now()+15m；casTake 超时守卫谓词）
    reschedule_of     VARCHAR(32)  NULL,                              -- 改期链原预约单号（改期=退旧号新，Task 6 消费）
    visit_id          VARCHAR(14)  NULL,                              -- 就诊号（取号后经 casTake 回填，O 型 14 位）
    status            VARCHAR(16)  NOT NULL DEFAULT 'RESERVED',       -- RESERVED 已预约占位/TAKEN 已取号/CANCELLED 已退号/NO_SHOW 爽约超时
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);

-- 预约单业务号唯一锚
CREATE UNIQUE INDEX uk_appt_no ON outpatient.appointment (appt_no);

-- 限购唯一锚：同患者同日同科仅一张有效单（RESERVED/TAKEN；退改终态行不占用唯一性，逻辑删行同此）
CREATE UNIQUE INDEX uk_appt_patient ON outpatient.appointment (patient_id, sched_date, dept_code)
    WHERE deleted = 0 AND status IN ('RESERVED', 'TAKEN');

CREATE TRIGGER trg_appointment_updated_at BEFORE UPDATE ON outpatient.appointment
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 就诊记录表
-- visit 主状态机载体（03 Spec 红线 5：迁移经状态机单点校验+visit_status_log 每迁必记）；
-- visit_id 为 CF-3 冻结结构（M03 唯一签发，签发后不可变、必须与 patient_id 同刻落库）。
CREATE TABLE outpatient.visit (
    id                 BIGINT       PRIMARY KEY,                      -- 雪花 ID（MP ASSIGN_ID）
    visit_id           VARCHAR(14)  NOT NULL,                         -- 就诊号（O+yyyyMMdd+5 位流水，uk_visit_id）
    patient_id         BIGINT       NOT NULL,                         -- 患者主索引（与 visit_id 同刻落库，CF-3 结论④）
    appt_id            BIGINT       NULL,                             -- 关联预约单 id（appointment.id；当日挂号亦建 appointment）
    dept_code          VARCHAR(64)  NOT NULL,                         -- 开诊科室编码（候诊队列与统计维度锚点）
    doctor_id          VARCHAR(64)  NULL,                             -- 接诊医生 id（约诊/当日挂号按排班回填）
    visit_type         VARCHAR(16)  NOT NULL,                         -- 就诊类型：EMERGENCY 急诊/GENERAL 普通/SPECIAL 专科/INTERNET 互联网/MDT 多学科/OTHER 其他
    is_revisit         SMALLINT     NOT NULL DEFAULT 0,               -- 是否复诊（0/1；号别 REVISIT 落 1）
    triage_level       INT          NULL,                             -- 急诊分级（Ⅰ~Ⅳ=1~4，分诊台写入）
    insurance_type     VARCHAR(32)  NULL,                             -- 医保类型，可空
    green_channel_flag SMALLINT     NOT NULL DEFAULT 0,               -- 绿通标记（声明列，P1 恒 0，绿通域随 P1 后续）
    registered_at      TIMESTAMPTZ  NOT NULL,                         -- 挂号/取号时间
    checked_in_at      TIMESTAMPTZ  NULL,                             -- 报到时间（分诊台/自助签到，国标采集）
    admitted_at        TIMESTAMPTZ  NULL,                             -- 接诊时间（国标采集）
    finished_at        TIMESTAMPTZ  NULL,                             -- 诊毕时间
    disposition        VARCHAR(32)  NULL,                             -- 离院去向（国标代码 1~7/9，诊毕写入）
    finish_operator    VARCHAR(64)  NULL,                             -- 诊毕操作者
    status             VARCHAR(16)  NOT NULL DEFAULT 'REGISTERED',    -- REGISTERED 已挂号/WAITING 候诊/IN_CONSULT 就诊中/PENDING_FEE 待缴费/IN_EXECUTION 执行中（声明态）/PENDING_MEDICATION 待取药（声明态）/FINISHED 诊毕/CANCELLED 已退号/NO_SHOW 爽约（声明态）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted            SMALLINT     NOT NULL DEFAULT 0
);

-- 就诊号唯一锚（CF-3：签发后不可变不可复用）
CREATE UNIQUE INDEX uk_visit_id ON outpatient.visit (visit_id);

-- 在途就诊查询（OngoingVisitQuery SPI：patient_id 维度，合并前置检查路径）
CREATE INDEX idx_visit_patient ON outpatient.visit (patient_id) WHERE deleted = 0;

CREATE TRIGGER trg_visit_updated_at BEFORE UPDATE ON outpatient.visit
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 爽约信用记录表
-- 爽约/超时信用台账（只增语义+解除留痕）：窗口内 NO_SHOW 计数达阈值即写 restrict_from~restrict_to
-- 限约区间（预约侧拦截 OP-1006），解除（到期自动/管理员手工）经 release_reason 留痕。
CREATE TABLE outpatient.appt_credit_record (
    id             BIGINT       PRIMARY KEY,                         -- 雪花 ID（MP ASSIGN_ID）
    patient_id     BIGINT       NOT NULL,                            -- 患者主索引（爽约主体归一后主档）
    action         VARCHAR(16)  NOT NULL,                            -- 动作：NO_SHOW 爽约超时/TIMEOUT_CANCEL 时限外取消
    occurred_at    TIMESTAMPTZ  NOT NULL,                            -- 发生时刻
    window_days    INT          NOT NULL,                            -- 记录时采用的统计窗口天数（参数留痕）
    restrict_from  DATE         NULL,                                -- 限约起始日（命中阈值时写当日）
    restrict_to    DATE         NULL,                                -- 限约截止日（含当日，今日+restrictDays）
    release_reason VARCHAR(255) NULL,                                -- 解除原因（信用解除留痕）
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

-- 信用窗口查询（预约限约拦截：patient_id+action+occurred_at 窗口计数）
CREATE INDEX idx_appt_credit_patient ON outpatient.appt_credit_record (patient_id, action) WHERE deleted = 0;

CREATE TRIGGER trg_appt_credit_record_updated_at BEFORE UPDATE ON outpatient.appt_credit_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 就诊状态迁移日志表（只增）
-- 03 Spec 红线 5：visit 全部迁移经状态机单点校验后每迁必记（from/to/reason/operator/occurred_at）；
-- 只增表零更新零逻辑删，无审计五列与触发器。
CREATE TABLE outpatient.visit_status_log (
    id          BIGINT       PRIMARY KEY,                            -- 雪花 ID（MP ASSIGN_ID）
    visit_id    VARCHAR(14)  NOT NULL,                               -- 就诊号（uk_visit_id 同源）
    from_status VARCHAR(16)  NOT NULL,                               -- 迁出态（VisitStatus code）
    to_status   VARCHAR(16)  NOT NULL,                               -- 迁入态（VisitStatus code）
    reason      VARCHAR(255) NULL,                                  -- 迁移原因，可空
    operator    VARCHAR(64)  NOT NULL,                               -- 操作者（portal 链路取哨兵值 PORTAL）
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now()                  -- 迁移时刻（库端默认）
);

-- 按就诊号回放迁移轨迹
CREATE INDEX idx_visit_status_log_visit ON outpatient.visit_status_log (visit_id);
