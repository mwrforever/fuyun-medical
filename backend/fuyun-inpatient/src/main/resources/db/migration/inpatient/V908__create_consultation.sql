-- V908：M04 会诊管理域单表（04-inpatient Spec §4/§5/§6/§7 FU-M04-09；P2 PR-1 Task 11；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   consultation 会诊单——院内会诊申请/响应/意见/超时升级闭环载体（调研依据 13：响应时限
--   行业实践为急会诊 30 分钟、普通会诊 24 小时）：
--     · 两条建单路径：POST /consultations 独立申请（受邀科室必填）+ CONSULT 类医嘱审核通过
--       钩子自动建草稿（order_ref 关联医嘱号，受邀科室待受邀科接单面明确——本列可空）；
--     · 超时升级为动作非状态迁移（Spec FU-M04-09 冻结口径）：读时惰性判定（REQUESTED 且
--       now > response_deadline 且 overdue_flag = false → 置标记并发布
--       inpatient.consultation.overdue 动作广播一次[DB 标记防重发]），状态停留 REQUESTED
--       仍可被响应（接单清标记）；fy.delay 档位扩展随 W-27 tick 方案 PR-4 一并设计
--       （五大降级清单④）——idx_consultation_deadline 即读时逾期扫描面；
--     · 会诊级别（level）：科内/院内/多学科 MDT（MDT 为预留词表值，Spec「会诊级别管理」
--       预留字段，P3 完整化）；
--     · 会诊意见（opinion）归档供 M09 病历引用（本 PR 零 M09 消费面，列表出参可见）。
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删、
--   状态 VARCHAR 常量；updated_at 触发器照挂（照 V602/V200 形态）。

CREATE TABLE inpatient.consultation (
    id                BIGINT        PRIMARY KEY,
    consult_no        VARCHAR(32)   NOT NULL,                      -- 会诊单号（CS+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("CS") 签发）
    visit_id          BIGINT        NOT NULL,                      -- 住院就诊主键（inpatient_visit.id——与 medical_order.visit_id 同口径，禁 I 型号入库）
    patient_id        BIGINT        NOT NULL,                      -- 患者主索引（事件载荷取数面）
    order_ref         VARCHAR(32)   NULL,                          -- 关联会诊医嘱号（CONSULT 类医嘱审核钩子自动建单落 order_no，审核重试幂等锚；独立申请为 NULL）
    from_dept_id      VARCHAR(64)   NULL,                          -- 申请科室编码（M01 组织 code；独立申请与钩子草稿同源取就诊 current_dept_id，缺失为 NULL）
    requester_id      VARCHAR(64)   NOT NULL,                      -- 申请医生（员工 ID string；独立申请=操作者上下文，钩子草稿=医嘱开立医生）
    to_dept_id        VARCHAR(64)   NULL,                          -- 受邀科室编码（M01 组织 code；独立申请必填，钩子草稿待受邀科接单面明确可为 NULL）
    level             VARCHAR(16)   NOT NULL DEFAULT 'DEPT',       -- 会诊级别词表：DEPT 科内 / HOSPITAL 院内 / MDT 多学科（MDT 预留，P3 完整化）
    urgency           VARCHAR(16)   NOT NULL,                      -- 紧急程度词表：URGENT 急会诊（响应时限 30 分钟）/ NORMAL 普通会诊（响应时限 24 小时）
    reason            VARCHAR(255)  NULL,                          -- 申请原因（cancelled 事件 reason 同源取本列；可空）
    requested_at      TIMESTAMPTZ   NOT NULL,                      -- 申请时点（应用服务器时钟）
    response_deadline TIMESTAMPTZ   NOT NULL,                      -- 响应截止时点（申请时点+响应时限：URGENT +30min / NORMAL +24h；idx 逾期扫描面）
    response_time     TIMESTAMPTZ   NULL,                          -- 受邀科接单时点（库端 now()，accept CAS 落值）
    consult_time      TIMESTAMPTZ   NULL,                          -- 会诊完成时点（库端 now()，opinion CAS 落值）
    opinion           VARCHAR(1000) NULL,                          -- 会诊意见（ACCEPTED→COMPLETED 意见提交落值；归档供 M09 引用）
    overdue_flag      BOOLEAN       NOT NULL DEFAULT false,        -- 逾期升级标记（读时惰性判定置位+overdue 动作事件一次的防重发锚；接单清零）
    status            VARCHAR(20)   NOT NULL DEFAULT 'REQUESTED',  -- REQUESTED 已申请待响应 / ACCEPTED 已接单 / COMPLETED 已完成（终态） / CANCELLED 已取消（终态）
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted           SMALLINT      NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.consultation IS '会诊单：院内会诊申请/响应/意见/超时升级闭环；CONSULT 类医嘱审核钩子自动建草稿（order_ref 关联）；超时升级为动作事件非状态迁移（状态停留 REQUESTED 仍可响应）';
CREATE UNIQUE INDEX uk_consult_no ON inpatient.consultation (consult_no) WHERE deleted = 0;
CREATE INDEX idx_consultation_deadline ON inpatient.consultation (response_deadline);
CREATE INDEX idx_consultation_status ON inpatient.consultation (status, requested_at) WHERE deleted = 0;
CREATE TRIGGER trg_consultation_updated_at BEFORE UPDATE ON inpatient.consultation
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
