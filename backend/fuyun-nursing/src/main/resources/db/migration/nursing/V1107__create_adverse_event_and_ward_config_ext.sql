-- V1107：M05 不良事件表 + 病区配置执行域六列（05-nursing Spec FU-M05-09 与 §4 ward_config 扩面；
--   P2 PR-3 Task 2；台账已先记再改）。登记义务：nursing.adverse-event.reported 事件登记见 V1109 id 83
--   （本迁移先行落表，消费面归 Task 10）。
-- 通用约定（V801/V905 同款）：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、
--   逻辑删、状态 VARCHAR 常量、普通表挂 public.fuyun_set_updated_at 触发器。

CREATE TABLE nursing.adverse_event (
    id                BIGINT       PRIMARY KEY,
    event_no          VARCHAR(32)  NOT NULL,                             -- 不良事件号（AE+yyyyMMdd+5 位流水，本模块签发）
    category          VARCHAR(32)  NOT NULL,                             -- 事件类别八词表：MEDICATION_ERROR 用药错误 / FALL 跌倒 / PRESSURE_ULCER 压疮 / TUBE_SLIP 管路滑脱 / BLOOD_TRANFUSION 输血 / DEVICE 器械 / FACILITY 设施 / OTHER 其他
    severity_class    VARCHAR(8)   NOT NULL,                             -- 严重度分级：I 级（最重）/ II / III / IV（最轻）——I/II 级触发 24 小时上报时限
    severity_grade    VARCHAR(2)   NOT NULL,                             -- 严重度等级：A~E（E=死亡，A=无害；与分级正交，上报报表双维度）
    ward_id           VARCHAR(64)  NOT NULL,                             -- 发生病区编码（与护理域各表同宽口径）
    visit_id          VARCHAR(14)  NULL,                                 -- 住院就诊号（可空：设施类事件可无就诊主体）
    patient_id        BIGINT       NULL,                                 -- 患者 ID（可空，同上；匿名上报不落患者明细面由应用层控制）
    occurred_at       TIMESTAMPTZ  NOT NULL,                             -- 事件发生时点（上报表单据实填报，非落库时点）
    event_summary     TEXT         NOT NULL,                             -- 事件经过（上报人据实描述）
    handling_note     TEXT         NOT NULL,                             -- 处置情况（上报时初步处置记录）
    reporter_id       BIGINT       NULL,                                 -- 上报人员工 ID（匿名通道上报为 NULL——配套 is_anonymous=true）
    is_anonymous      BOOLEAN      NOT NULL DEFAULT false,               -- 匿名上报标识（true=匿名通道，不落上报人）
    report_deadline   TIMESTAMPTZ  NULL,                                 -- 上报时限基准（I/II 级=occurred_at+24h；III/IV 级时限口径归应用层规则承载，列不预置）
    deadline_met      BOOLEAN      NULL,                                 -- 时限达成（时限判定动作落值；未判定为 NULL）
    status            VARCHAR(16)  NOT NULL DEFAULT 'REPORTED',          -- 处置状态：REPORTED 已上报 / HANDLING 处置中 / CLOSED 已关闭
    handler_id        BIGINT       NULL,                                 -- 处置责任人员工 ID（进入 HANDLING 落值）
    rca_note          TEXT         NULL,                                 -- 根因分析（RCA）记录（关闭前按需补录）
    corrective_action TEXT         NULL,                                 -- 整改措施（关闭前按需补录）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.adverse_event IS '护理不良事件（上报-处置-关闭全周期；匿名通道与 24 小时时限合规；统计面归 M19 护理质量指标）';
CREATE UNIQUE INDEX uk_adverse_event_no ON nursing.adverse_event (event_no) WHERE deleted = 0;
CREATE TRIGGER trg_adverse_event_updated_at BEFORE UPDATE ON nursing.adverse_event
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ===================== nursing_ward_config 执行域扩面六列（P1 头注遗留义务兑现） =====================
-- V801 头注「执行域四字段随 P2 迁移追加」兑现 + 消费后置两列；全部为可缺省配置面，无回填义务。
ALTER TABLE nursing.nursing_ward_config ADD COLUMN execute_time_window_minutes INT NOT NULL DEFAULT 30;
COMMENT ON COLUMN nursing.nursing_ward_config.execute_time_window_minutes IS '执行时间窗（分钟）：计划时间 ± 窗口内允许签收执行，窗外拦截提示；缺省 30';

ALTER TABLE nursing.nursing_ward_config ADD COLUMN override_roles VARCHAR(255) NOT NULL DEFAULT 'HEAD_NURSE';
COMMENT ON COLUMN nursing.nursing_ward_config.override_roles IS '破码放行授权角色（逗号分隔角色码，扫码核对失败后放行所需授权；缺省护士长）';

ALTER TABLE nursing.nursing_ward_config ADD COLUMN routine_task_templates JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN nursing.nursing_ward_config.routine_task_templates IS '常规模板：[{templateCode,name,frequencyMinutes,taskType}]（翻身边/口腔护理等周期能力，FU-M05-07 批量生成消费）';

ALTER TABLE nursing.nursing_ward_config ADD COLUMN return_drug_enabled BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN nursing.nursing_ward_config.return_drug_enabled IS '退药开关（true=病区允许出院/停嘱后退药回补，M06 摆药域联动消费）';

ALTER TABLE nursing.nursing_ward_config ADD COLUMN iot_sync_interval_seconds INT NULL;
COMMENT ON COLUMN nursing.nursing_ward_config.iot_sync_interval_seconds IS 'IoT 数据同步间隔（秒）——列落消费后置：本迁移仅落列面，消费逻辑随 Task 12 联动回接实装';

ALTER TABLE nursing.nursing_ward_config ADD COLUMN conflict_window_seconds INT NULL;
COMMENT ON COLUMN nursing.nursing_ward_config.conflict_window_seconds IS '同刻冲突窗口（秒）——列落消费后置：本迁移仅落列面，并发核对/录入口径随执行域任务实装';
