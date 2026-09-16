-- V101：疑似重复待审表 + 合并记录表（M02 Spec §4，FU-M02-03 载体）。
-- 号段登记：patient 域 V100–V199（CHANGELOG 2026-09-16 条目），本文件为 V101。
-- M-1 修订落点：merge_record 的 (merged_patient_id) 为部分唯一约束——仅 PROCESSING/COMPLETED 生效，
--   置 REVERSED/FAILED 后同一从档允许再次发起合并（「拆分-再合并可循环」，M02 §10 边界用例）。

CREATE TABLE patient.possible_duplicate (
    id             BIGINT        PRIMARY KEY,                  -- 雪花 ID（MP ASSIGN_ID）
    patient_id_a   BIGINT        NOT NULL,                     -- 患者对 A（应用层保证 a < b，防同对两行镜像）
    patient_id_b   BIGINT        NOT NULL,                     -- 患者对 B
    match_score    NUMERIC(5,2)  NOT NULL,                     -- 匹配评分 0-100（弱标识加权 / 强标识矛盾固定 100）
    matched_rules  VARCHAR(512)  NOT NULL,                     -- 命中字段与相似度快照（JSON 数组文本：[{"rule":"NAME_SEX_BIRTH","score":95}]）
    source         VARCHAR(16)   NOT NULL,                     -- 来源：REGISTER_SCAN 建档实时 / BATCH_SCAN 批量扫描
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',   -- 状态机：PENDING/MERGED(按合并处理)/EXCLUDED(排除必填理由)
    reviewed_by    VARCHAR(64)   NULL,                         -- 审核人（审核动作落）
    reviewed_at    TIMESTAMPTZ   NULL,                         -- 审核时刻
    review_note    VARCHAR(255)  NULL,                         -- 审核备注（EXCLUDED 时必填理由）
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted        SMALLINT      NOT NULL DEFAULT 0
);

-- 同一对患者仅一条待审（逻辑删行不占用；批量扫描重跑幂等兜底）
CREATE UNIQUE INDEX uk_possible_duplicate_pair
    ON patient.possible_duplicate (patient_id_a, patient_id_b) WHERE deleted = 0;
-- 待审工作台主检索（GET /possible-duplicates?status=PENDING）
CREATE INDEX idx_possible_duplicate_status ON patient.possible_duplicate (status) WHERE deleted = 0;

CREATE TRIGGER trg_possible_duplicate_updated_at BEFORE UPDATE ON patient.possible_duplicate
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE patient.merge_record (
    id                   BIGINT        PRIMARY KEY,            -- 雪花 ID（MP ASSIGN_ID）
    survivor_patient_id  BIGINT        NOT NULL,               -- 主档（合并后保留方）
    merged_patient_id    BIGINT        NOT NULL,               -- 从档（被合并方，置 MERGED）
    merge_reason         VARCHAR(255)  NOT NULL,               -- 合并原因（审计必填）
    pre_snapshot         TEXT          NOT NULL,               -- 合并前完整快照（JSON：从档字段 + 标识挂接清单；拆分回滚依据，方案 3.3）
    status               VARCHAR(16)   NOT NULL DEFAULT 'PROCESSING', -- 状态机：PROCESSING/COMPLETED/FAILED(可重试)/REVERSED(终态)
    operator             VARCHAR(64)   NOT NULL,               -- 经办人（双人角色：与 approved_by 不得同人，应用层校验）
    approved_by          VARCHAR(64)   NULL,                   -- 审批人（approve 动作落）
    completed_at         TIMESTAMPTZ   NULL,                   -- 合并完成时刻（COMPLETED 落）
    reversed_at          TIMESTAMPTZ   NULL,                   -- 拆分时刻（REVERSED 落）
    reverse_reason       VARCHAR(255)  NULL,                   -- 拆分原因
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by           VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted              SMALLINT      NOT NULL DEFAULT 0
);

-- 活跃合并期内一档只能被合并一次（M-1：仅 PROCESSING/COMPLETED 生效；REVERSED/FAILED 不阻断再次合并）
CREATE UNIQUE INDEX uk_merge_record_merged_active
    ON patient.merge_record (merged_patient_id) WHERE status IN ('PROCESSING', 'COMPLETED') AND deleted = 0;
-- 按主档回溯其合并历史（拆分入口检索）
CREATE INDEX idx_merge_record_survivor ON patient.merge_record (survivor_patient_id);

CREATE TRIGGER trg_merge_record_updated_at BEFORE UPDATE ON patient.merge_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
