-- V806：M05 护理评估单表（05-nursing Spec §4 nursing_assessment / 调研依据 9 量表体系）
--   量表定义以模块内 Java 常量承载（Spec :249「量表为模块专业配置非国标字典」），落库仅存条目应答快照与判级结果；
--   CUSTOM 自定义量表引擎归 P2（NS-1009 拒绝）。
--   高风险联动：自动生成防范任务（nursing_task task_type=PREVENTION）并回写病区视图 risk_flags。

CREATE TABLE nursing.nursing_assessment (
    id                 BIGINT       PRIMARY KEY,
    assess_no          VARCHAR(32)  NOT NULL,                              -- 评估单号（AS+yyyyMMdd+5 位流水）
    patient_id         BIGINT       NOT NULL,                              -- 患者 ID
    visit_id           VARCHAR(14)  NOT NULL,                              -- 住院就诊号（I 型）
    ward_id            VARCHAR(64)  NOT NULL,                              -- 病区编码
    scale_type         VARCHAR(16)  NOT NULL,                              -- BRADEN 压疮 / MORSE 跌倒 / NRS 疼痛 / BARTHEL 自理能力 / MEWS 早期预警
    answers            JSONB        NOT NULL,                              -- 条目应答快照 {"itemCode": score, ...}
    total_score        INT          NOT NULL,                              -- 总分
    risk_level         VARCHAR(16)  NOT NULL,                              -- HIGH 高风险 / MEDIUM 中风险 / LOW 低风险
    assessed_at        TIMESTAMPTZ  NOT NULL,                              -- 评估时点
    assessed_by        VARCHAR(64)  NOT NULL,                              -- 评估人
    next_assess_plan   TIMESTAMPTZ  NULL,                                  -- 下次复评计划（按风险等级周期）
    triggered_task_ref VARCHAR(32)  NULL,                                  -- 防范任务引用（nursing_task.task_no）
    adverse_event_ref  VARCHAR(32)  NULL,                                  -- 事件后回评引用（FU-M05-09 归 P2，P1 空列）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted            SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.nursing_assessment IS '护理评估单（量表引擎；高危自动生成防范任务与床旁风险标识）';
CREATE UNIQUE INDEX uk_nursing_assessment_no ON nursing.nursing_assessment (assess_no) WHERE deleted = 0;
CREATE INDEX idx_nursing_assessment_visit ON nursing.nursing_assessment (visit_id, scale_type, assessed_at);
CREATE TRIGGER trg_nursing_assessment_updated_at BEFORE UPDATE ON nursing.nursing_assessment
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
