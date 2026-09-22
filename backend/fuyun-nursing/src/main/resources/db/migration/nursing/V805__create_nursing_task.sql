-- V805：M05 护理任务表（05-nursing Spec §4 nursing_task；P1 仅落「任务最小载体」——
--   FU-M05-07 任务工作台（分组/认领/常规模板批量生成/逾期升级链/延迟队列）归 P2，本 PR 不做）
-- 逾期口径（Spec :127 动作式逾期 + §12-3）：overdue_flag + escalation_count 为动作落点；
--   P1 由查询侧惰性判定（NursingProperties.taskOverdueMinutes，默认 30 分钟）单次递增；
--   P2 由 delay.task-overdue 延迟队列驱动并发布 nursing.task.overdue（本 PR 已在 V800 占位登记）。

CREATE TABLE nursing.nursing_task (
    id               BIGINT       PRIMARY KEY,
    task_no          VARCHAR(32)  NOT NULL,                                -- 任务号（TK+yyyyMMdd+5 位流水）
    patient_id       BIGINT       NOT NULL,                                -- 患者 ID
    visit_id         VARCHAR(14)  NOT NULL,                                -- 住院就诊号（I 型）
    ward_id          VARCHAR(64)  NOT NULL,                                -- 病区编码
    bed_no           VARCHAR(32)  NULL,                                    -- 床位号（冗余展示）
    task_type        VARCHAR(32)  NOT NULL,                                -- MEDICATION 给药/INFUSION_CARE 输液护理/TURN 翻身/PATROL 巡视/SPECIMEN 标本采集/IO_MONITOR 出入量监测/IOT_LINKAGE IoT 联动/ASSESS_REMIND 评估提醒/MANUAL 手工
    source           VARCHAR(16)  NOT NULL,                                -- ORDER_PLAN 医嘱计划（P2）/INFUSION_ALARM 输液告急（P2）/IOT_LINKAGE（P2）/ROUTINE 常规（P2）/MANUAL 手工/ASSESSMENT 评估联动（Task 8）
    source_ref       VARCHAR(64)  NULL,                                    -- 来源引用（执行单号/告警号/规则号/评估单号）
    plan_time        TIMESTAMPTZ  NOT NULL,                                -- 计划时间（逾期判定基准）
    assigned_nurse   VARCHAR(64)  NULL,                                    -- 责任护士（空=未指派，由任务列表按责任组展示）
    priority         VARCHAR(8)   NOT NULL DEFAULT 'NORMAL',               -- HIGH/NORMAL/LOW
    overdue_flag     BOOLEAN      NOT NULL DEFAULT false,                  -- 逾期标记（动作式，非状态）
    escalation_count INT          NOT NULL DEFAULT 0,                      -- 升级次数（P1 惰性判定单次递增）
    completed_at     TIMESTAMPTZ  NULL,                                    -- 完成时间
    cancel_reason    VARCHAR(255) NULL,                                    -- 取消原因（取消必填）
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',              -- PENDING/IN_PROGRESS/COMPLETED/CANCELLED
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted          SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.nursing_task IS '护理任务统一载体（给药/输液/翻身/巡视/评估提醒等；任务工作台归 P2）';
CREATE UNIQUE INDEX uk_nursing_task_no ON nursing.nursing_task (task_no) WHERE deleted = 0;
CREATE INDEX idx_nursing_task_ward_status_plan ON nursing.nursing_task (ward_id, status, plan_time);
CREATE INDEX idx_nursing_task_visit_status ON nursing.nursing_task (visit_id, status);
CREATE TRIGGER trg_nursing_task_updated_at BEFORE UPDATE ON nursing.nursing_task
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
