-- V1000：M06 住院用药审方薄切片两表（P2 PR-1 Task 12；台账 docs/migrations/flyway-version-registry.md
--   已预登记 V1000 行，先记再改）。
-- 号段口径：pharmacy 固定段 V700–V799 内任何号 ≤V799 小于基线全局最大 V900，乱序守卫必拦——
--   本迁移取 V500+ 通用段四位数首例 V1000（同时避开 inpatient 专属段 V900–V999 防未来撞车）。
-- 数据来源：消费 inpatient.order.created（drug 子键）事件落快照——事件载荷为唯一权威（禁跨模块
--   读 M04 表，模块依赖单向红线）；患者姓名/诊断文本不入库不出网（脱敏红线，载荷契约亦不携带）。
--   审方规则引擎/毒麻/抗菌药完整化/双签为 P3（薄切片=全部人工审方）。
-- 幂等双层：uk_medication_order_no（同一医嘱仅一条用药快照）+ uk_review_medication（同一快照
--   仅一张审方任务）——重复投递/重提重发事件经唯一约束兜底「重复消费仅一任务」。

-- ===================== 一、order_medication：住院医嘱用药快照（消费 drug 子键落库面） =====================
CREATE TABLE pharmacy.order_medication (
    id            BIGINT        PRIMARY KEY,
    m04_order_no  VARCHAR(32)   NOT NULL,      -- 住院医嘱号（M04 medical_order.order_no；审方回执 target 定位键）
    visit_id      VARCHAR(14)   NOT NULL,      -- 住院就诊号（I 型 14 位，M02 结构规范）
    patient_id    BIGINT        NOT NULL,      -- 患者主索引（M02）
    freq_code     VARCHAR(32)   NULL,          -- 频次编码（长期医嘱非空、临时医嘱可空；快照自事件载荷 freqCode）
    items         TEXT          NOT NULL DEFAULT '[]', -- 医嘱项明细快照 JSON 数组（itemSeq/itemCode/itemName/dosage/unit/route/quantity/itemType——仅药品/剂量/途径/数量，禁患者姓名/诊断）
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted       SMALLINT      NOT NULL DEFAULT 0
);

-- 快照唯一键：同一住院医嘱仅一条用药快照（重提重发事件幂等兜底第一道防线）
CREATE UNIQUE INDEX uk_medication_order_no ON pharmacy.order_medication (m04_order_no) WHERE deleted = 0;

CREATE TRIGGER trg_order_medication_updated_at BEFORE UPDATE ON pharmacy.order_medication
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ===================== 二、review_task：药师审方任务（工作台人工审 + 回执发布源） =====================
CREATE TABLE pharmacy.review_task (
    id                  BIGINT        PRIMARY KEY,
    order_medication_id BIGINT        NOT NULL,  -- 关联用药快照（uk 一快照一任务，重提重开=同任务复位非新建）
    apply_dept          VARCHAR(64)   NULL,      -- 申请科室（M01 组织 code；事件契约未携带，随 CF-6 契约扩展回填，薄切片期 NULL）
    apply_doctor        VARCHAR(64)   NULL,      -- 申请医生（员工工号；事件契约未携带，随 CF-6 契约扩展回填，薄切片期 NULL）
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING', -- PENDING 待审/APPROVED 已通过/REJECTED 已驳回（三态小状态机，PENDING 唯一可决出边）
    pharmacist_id       VARCHAR(64)   NULL,      -- 审方药师（员工工号，决策回写；即回执 auditOperator）
    opinion             VARCHAR(512)  NULL,      -- 药师意见（驳回必附——回执 rejectReason；通过可选）
    decided_at          TIMESTAMPTZ   NULL,      -- 决策时刻（审方通过/驳回回写）
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by          VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted             SMALLINT      NOT NULL DEFAULT 0
);

-- 任务唯一键：同一用药快照仅一张审方任务（「重复消费仅一任务」硬防线）
CREATE UNIQUE INDEX uk_review_medication ON pharmacy.review_task (order_medication_id) WHERE deleted = 0;
-- 工作台列表高频过滤（状态过滤 + 先到先审 FIFO 序）
CREATE INDEX idx_review_task_status ON pharmacy.review_task (status, id) WHERE deleted = 0;

CREATE TRIGGER trg_review_task_updated_at BEFORE UPDATE ON pharmacy.review_task
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
