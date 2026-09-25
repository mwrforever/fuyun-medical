-- V1002：挂账审批表 + 事件契约 id 73 种子（M13 住院计费联动，P2 PR-1 Task 13；通用段四位数，
--   理由同 V1001，台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--
-- 业务定位：出院欠费挂账审批实体（Spec M-10 四态：DRAFT 草稿/PENDING_APPROVAL 待审批/
--   APPROVED 已通过/REJECTED 已驳回）——预审 BLOCKED 的出院申请经审批人放行后发布
--   billing.arrears.approved（id 73），M04 消费将 discharge_request BLOCKED→READY
--   （approval_no 放行凭证留痕）。approved_balance 为审批时点押金余额快照（分），
--   即 id 73 载荷 approvedBalance 同源承载。
--
-- 事件登记（全局递增接 V901 id 72；status 词表 ACTIVE/DEPRECATED，V2:13）：
--   desc 照计划前置项 13 行文「M13 住院侧契约冻结与联调（六事件消费+计价/停费/归属切分+arrears）」，
--   载荷四字段与 api ArrearsApprovedPayload record 组件逐字同源（三方一致红线，消费方
--   inpatient BillingEventListener JsonNode 读 visitId/approvalNo——Task 9 冻结面）。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（V605/V901 先例）。

CREATE TABLE billing.arrears_approval (
    id               BIGINT       PRIMARY KEY,            -- 雪花（实体 ASSIGN_ID）
    approval_no      VARCHAR(64)  NOT NULL,               -- 审批单号（AR+服务端序列号，业务唯一）
    visit_id         VARCHAR(14)  NOT NULL,               -- CF-3 住院就诊号（I 型 14 位）
    apply_reason     VARCHAR(255) NOT NULL,               -- 申请理由（欠费挂账缘由）
    approver         VARCHAR(64)  NULL,                   -- 审批人（操作者上下文注入；决定时回填）
    decided_at       TIMESTAMPTZ  NULL,                   -- 决定时间（通过/驳回同语句落，库端 now()）
    approved_balance BIGINT       NULL,                   -- 批准时点押金余额快照（分；APPROVED 必填，id 73 载荷 approvedBalance 同源）
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT', -- 四态词表（Spec M-10）：DRAFT 草稿/PENDING_APPROVAL 待审批/APPROVED 已通过（发布 id 73）/REJECTED 已驳回
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted          SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_arrears_approval_no ON billing.arrears_approval (approval_no) WHERE deleted = 0;
-- 高频读路径（出院域按就诊号反查放行凭证）
CREATE INDEX idx_arrears_approval_visit ON billing.arrears_approval (visit_id) WHERE deleted = 0;

CREATE TRIGGER trg_arrears_approval_updated_at BEFORE UPDATE ON billing.arrears_approval
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- id 73：billing.arrears.approved（producer=billing，全局递增接 inpatient 段 id 72）
INSERT INTO integration.event_registry (id, event_type, producer_module, payload_desc, status)
SELECT 73, 'billing.arrears.approved', 'billing',
       '挂账审批放行——M13 住院侧契约冻结与联调（六事件消费+计价/停费/归属切分+arrears）：visitId/approvalNo/approvedAt/approvedBalance(审批时点押金余额快照,分)；M04 消费解除出院费用拦截（discharge_request BLOCKED→READY，approval_no 放行凭证留痕）（P2 PR-1 实装）',
       'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM integration.event_registry WHERE event_type = 'billing.arrears.approved' AND deleted = 0);
