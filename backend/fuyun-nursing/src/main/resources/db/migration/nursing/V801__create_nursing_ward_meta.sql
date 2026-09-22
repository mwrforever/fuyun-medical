-- V801：M05 病区元数据域三表（05-nursing Spec §4；P1 切片）
--   ① nursing_ward_config  病区护理策略唯一配置点（P1 仅落 P1 消费面：体征测量频次参数组 / IoT 自动落卡
--      开关（P2 生效注记）/ 班次定义（交接班消费）；执行域四字段 execute_time_window / override_roles /
--      routine_task_templates / 退药开关随 P2 迁移追加，避免无消费者空转列）
--   ② nurse_assignment     责任护士分配（FU-M05-01；同床位同班次唯一 / 同患者同班次唯一）
--   ③ nursing_ward_patient 病区患者本地视图【临时（P1 过渡）】（Spec :145「订阅…维护本地视图」授权的落点；
--      P1 由过渡通道 POST /api/v1/nursing/ward-patients 写入；**退役触发条件 = M04 病区/床位事件链
--      （inpatient.visit.admitted/transferred/discharged + inpatient.bed.changed）上线并通过其验收 IT**；
--      退役义务双侧留痕：05-nursing Spec 注记 + 04-inpatient Spec 注记 + TASK.md 工单，三处同文）
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删、状态 VARCHAR 常量。

CREATE TABLE nursing.nursing_ward_config (
    id                   BIGINT       PRIMARY KEY,
    ward_id              VARCHAR(64)  NOT NULL,                            -- 病区编码（M01 组织机构病区 code）
    vital_freq_config    JSONB        NOT NULL DEFAULT '{"SPECIAL":60,"CRITICAL":240,"NORMAL":480}',  -- 体征测量频次（分钟/次，按护理级别；文书记录频次提醒取此值）
    iot_autocast_enabled BOOLEAN      NOT NULL DEFAULT false,              -- IoT 自动落卡开关（P2 生效；ICU 病区经此关闭防双写）
    shift_definitions    JSONB        NOT NULL,                            -- 班次定义：[{"code","name","start","end"}]
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by           VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted              SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.nursing_ward_config IS '病区护理配置（病区级策略唯一配置点；一病区一行）';
CREATE UNIQUE INDEX uk_ward_config_ward ON nursing.nursing_ward_config (ward_id) WHERE deleted = 0;
CREATE TRIGGER trg_ward_config_updated_at BEFORE UPDATE ON nursing.nursing_ward_config
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.nurse_assignment (
    id              BIGINT       PRIMARY KEY,
    ward_id         VARCHAR(64)  NOT NULL,                                 -- 病区编码
    nurse_id        VARCHAR(64)  NOT NULL,                                 -- 护士标识（M01 用户标识；与审计列/操作者口径统一为 VARCHAR(64)）
    assignment_type VARCHAR(16)  NOT NULL,                                 -- PRIMARY 责任组 / BED 管床
    shift_code      VARCHAR(32)  NOT NULL,                                 -- 班次 code（取 nursing_ward_config.shift_definitions）
    bed_no          VARCHAR(32)  NULL,                                     -- 管床床位号（assignment_type=BED 时必填）
    patient_id      BIGINT       NULL,                                     -- 责任患者（assignment_type=PRIMARY 时必填）
    valid_from      DATE         NOT NULL,                                 -- 生效日期
    valid_to        DATE         NULL,                                     -- 失效日期（空=长期）
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',                -- ACTIVE 生效 / CANCELLED 已撤销
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.nurse_assignment IS '责任护士分配（护士↔床位/患者 × 班次；大屏管床与任务派发依据）';
CREATE UNIQUE INDEX uk_assignment_bed_shift ON nursing.nurse_assignment (ward_id, bed_no, shift_code, valid_from)
    WHERE deleted = 0 AND bed_no IS NOT NULL AND status = 'ACTIVE';
CREATE UNIQUE INDEX uk_assignment_patient_shift ON nursing.nurse_assignment (ward_id, patient_id, shift_code, valid_from)
    WHERE deleted = 0 AND patient_id IS NOT NULL AND status = 'ACTIVE';
CREATE TRIGGER trg_nurse_assignment_updated_at BEFORE UPDATE ON nursing.nurse_assignment
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.nursing_ward_patient (
    id             BIGINT       PRIMARY KEY,
    ward_id        VARCHAR(64)  NOT NULL,                                  -- 病区编码
    bed_no         VARCHAR(32)  NOT NULL,                                  -- 床位号
    patient_id     BIGINT       NOT NULL,                                  -- 患者 ID（MERGED 时收敛主档，CF-3 语义）
    visit_id       VARCHAR(14)  NOT NULL,                                  -- 住院就诊号（I 型 14 位，签发主体 M04；M05 仅结构校验）
    patient_name   VARCHAR(64)  NOT NULL,                                  -- 患者展示名（P1 过渡通道由操作者录入，P2 事件链携带）
    gender         VARCHAR(8)   NULL,                                      -- 性别 code（M01 字典）
    age            INT          NULL,                                      -- 年龄（岁）
    nursing_level  VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',                 -- 护理级别：SPECIAL 特级 / CRITICAL 病重 / NORMAL 普通（权威在 M04，本表为视图属性）
    condition_tags VARCHAR(255) NOT NULL DEFAULT '',                       -- 病情状态标记（逗号分隔：CRITICAL 危/SEVERE 重/NEW 新入/SURGERY 手术/DELIVERY 分娩；**M04 事件派生的展示镜像**——ADT 语义标记（转出/今日出院等）随 P2 事件链写入，P1 无生产者，M05 不据此承担任何 ADT 权威）
    allergy_flag   BOOLEAN      NOT NULL DEFAULT false,                    -- 过敏标识（patient.health-summary.updated 订阅刷新；详情卡另经 AllergyChecker 实时嵌查）
    risk_flags     VARCHAR(255) NOT NULL DEFAULT '',                       -- 风险标识（逗号分隔：FALL 跌倒/PRESSURE 压疮；来自评估单高危结果，Task 8 经 appendRiskFlag 回写；TUBE 管路风险来自执行域管路管理，P1 无写入方——P2 注记）
    admitted_at    TIMESTAMPTZ  NOT NULL,                                  -- 入区时间
    status         VARCHAR(16)  NOT NULL DEFAULT 'IN_WARD',                -- IN_WARD 在区（视图语义）/ REMOVED 已移出病区一览（非 ADT 状态；出院/转科/换床语义归 M04，语义锁见 GC38）
    source         VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',                 -- MANUAL P1 过渡通道 / EVENT P2 事件链
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.nursing_ward_patient IS '病区患者本地视图（P1 过渡通道写入，P2 由 inpatient.visit.* + bed.changed 事件链替代）';
CREATE UNIQUE INDEX uk_ward_patient_visit ON nursing.nursing_ward_patient (visit_id)
    WHERE deleted = 0 AND status = 'IN_WARD';
CREATE UNIQUE INDEX uk_ward_patient_bed ON nursing.nursing_ward_patient (ward_id, bed_no)
    WHERE deleted = 0 AND status = 'IN_WARD';
CREATE INDEX idx_ward_patient_ward_status ON nursing.nursing_ward_patient (ward_id, status);
CREATE TRIGGER trg_ward_patient_updated_at BEFORE UPDATE ON nursing.nursing_ward_patient
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 演示病区种子（正式病区 code 取 M01 组织机构病区编码，P2 对齐；本行仅为 P1 演示与联调提供配置载体）
INSERT INTO nursing.nursing_ward_config (id, ward_id, shift_definitions, created_by, updated_by)
SELECT 1, 'W01',
       '[{"code":"DAY","name":"白班","start":"08:00","end":"16:00"},{"code":"EVENING","name":"小夜班","start":"16:00","end":"24:00"},{"code":"NIGHT","name":"大夜班","start":"00:00","end":"08:00"}]'::jsonb,
       'system', 'system'
WHERE NOT EXISTS (SELECT 1 FROM nursing.nursing_ward_config WHERE ward_id = 'W01' AND deleted = 0);
