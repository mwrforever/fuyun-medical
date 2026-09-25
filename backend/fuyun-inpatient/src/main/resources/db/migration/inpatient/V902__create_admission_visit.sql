-- V902：M04 入院登记域两表（04-inpatient Spec §4；P2 PR-1 Task 3；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   ① admission     住院证（入院申请）——待入院队列主体：登记即建单入 WAITING 队列；
--      schedule 预约入院置 SCHEDULED 并记录目标床位/预约日期（本表先承载目标面数据；
--      床位 RESERVED 预占联动调 BedService 归 Task 4 随 V903 bed 落地后补齐）
--   ② inpatient_visit 住院就诊就业主实体——入院登记确认（register）同事务签发 I 型 14 位
--      visit_id（M02 Spec 红线 1：I 型唯一签发主体 = 本模块）并落 REGISTERED 行；
--      uk_visit_id 兜底签发幂等；转科/转床仅变更 current_ward/current_bed 不改状态（后续任务）。
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删、状态 VARCHAR 常量。

CREATE TABLE inpatient.admission (
    id                BIGINT       PRIMARY KEY,
    admission_no      VARCHAR(32)  NOT NULL,                            -- 住院证号（AD+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("AD") 签发）
    patient_id        BIGINT       NOT NULL,                            -- 患者主索引（经 PatientContextResolver 归一后落库，CF-3）
    source_type       VARCHAR(16)  NOT NULL,                            -- 来源：OUTPATIENT 门诊转诊 / EMERGENCY 急诊 / PEIS 体检 / OTHER 其他
    source_visit_id   VARCHAR(14)  NULL,                                -- 门诊 visit_id 引用（O 型 14 位；source_type=OUTPATIENT 转诊关联，两 visit 各自独立）
    target_dept_id    VARCHAR(64)  NULL,                                -- 目标科室编码（M01 组织机构 code）
    target_ward_id    VARCHAR(64)  NULL,                                -- 目标病区编码（schedule 预约写入）
    target_bed_id     BIGINT       NULL,                                -- 目标床位 id（schedule 预约写入；bed 表归 V903/Task 4，本列先承载引用值）
    admission_type    VARCHAR(16)  NOT NULL,                            -- 入院类型：NORMAL 普通 / EMERGENCY 急诊 / PRE_HOSPITAL 预住院（队列排序第一键=急诊优先）
    expect_date       DATE         NULL,                                -- 预约入院日期（队列排序第二键=预约时段；缺省 NULL 排后）
    diagnosis_summary VARCHAR(255) NULL,                                -- 入院诊断摘要（register 时誊写至 inpatient_visit.admission_diagnosis；敏感文本禁入事件载荷——脱敏红线）
    issued_doctor_id  VARCHAR(64)  NOT NULL,                            -- 开证医生（M01 用户标识；与审计列/操作者口径统一为 VARCHAR(64)）
    status            VARCHAR(20)  NOT NULL DEFAULT 'WAITING',          -- WAITING 待入院 / SCHEDULED 已预约 / COMPLETED 已登记 / CANCELLED 已作废（四态词表，状态机见 Spec §5）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.admission IS '住院证（入院申请）：待入院队列主体，登记即建单；admission 1:0..1 inpatient_visit';
CREATE UNIQUE INDEX uk_admission_no ON inpatient.admission (admission_no) WHERE deleted = 0;
CREATE INDEX idx_admission_queue ON inpatient.admission (status, created_at);
CREATE TRIGGER trg_admission_updated_at BEFORE UPDATE ON inpatient.admission
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.inpatient_visit (
    id                     BIGINT       PRIMARY KEY,
    admission_id           BIGINT       NOT NULL,                       -- 关联住院证 id（admission 1:0..1 inpatient_visit）
    visit_id               VARCHAR(14)  NOT NULL,                       -- 住院就诊号（I+yyyyMMdd+5 位流水，定长 14 位，M02 结构规范；本模块唯一签发主体）
    patient_id             BIGINT       NOT NULL,                       -- 患者主索引（签发时点归一主档，与 visit_id 同事务同时落库——M02 结论 ④）
    current_dept_id        VARCHAR(64)  NULL,                           -- 当前科室编码（入科确认写入；转科变更）
    current_ward_id        VARCHAR(64)  NULL,                           -- 当前病区编码（入科确认写入；转科/转床变更）
    current_bed_id         BIGINT       NULL,                           -- 当前床位 id（入科确认写入；bed 表归 V903/Task 4）
    attending_doctor_id    VARCHAR(64)  NULL,                           -- 主治医生（入科确认写入）
    nursing_level          VARCHAR(16)  NULL,                           -- 护理级别：SPECIAL 特级 / CRITICAL 病重 / NORMAL 普通（入科确认写入；权威在本表，M05 为视图镜像）
    insurance_type         VARCHAR(32)  NOT NULL,                       -- 医保类型（险种标识，M01 字典 code；register 登记并随事件外发）
    admission_diagnosis    VARCHAR(255) NULL,                           -- 入院诊断（register 自住院证誊写；敏感文本禁入事件载荷——脱敏红线）
    registered_at          TIMESTAMPTZ  NOT NULL,                       -- 登记确认时点（visit_id 签发时点，应用服务器时钟）
    admitted_at            TIMESTAMPTZ  NULL,                           -- 入科确认时点（库端 now()，禁应用时钟）
    discharge_requested_at TIMESTAMPTZ  NULL,                           -- 出院申请时点（Task 9 写入）
    discharged_at          TIMESTAMPTZ  NULL,                           -- 出院完成时点（Task 9 写入）
    discharge_way          VARCHAR(16)  NULL,                           -- 离院方式（病案首页代码，Task 9 写入）
    arrears_flag           BOOLEAN      NOT NULL DEFAULT false,         -- 欠费标识（Task 10 消费 billing.deposit.changed 刷新）
    status                 VARCHAR(20)  NOT NULL DEFAULT 'REGISTERED',  -- REGISTERED 已登记待入科 / ADMITTED 在院 / DISCHARGE_REQUESTED 出院申请中 / DISCHARGED 已出院 / CANCELLED 已作废（五态词表）
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by             VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted                SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.inpatient_visit IS '住院就诊（就业主实体）：I 型 visit_id 唯一签发同事务落库；转科/转床仅变更 current_* 不改状态';
CREATE UNIQUE INDEX uk_visit_id ON inpatient.inpatient_visit (visit_id) WHERE deleted = 0;
CREATE INDEX idx_visit_patient_status ON inpatient.inpatient_visit (patient_id, status);
CREATE TRIGGER trg_inpatient_visit_updated_at BEFORE UPDATE ON inpatient.inpatient_visit
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
