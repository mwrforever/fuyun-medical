-- V905：M04 医嘱审核与控制域两表 + medical_order 增列（04-inpatient Spec §4/§3.3/§6；P2 PR-1 Task 6；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   ① order_audit       医嘱审核流水——系统自动审核（SYSTEM）与药师审方（PHARMACIST）双阶段结论
--                       留痕：用药类开立即落 SYSTEM 预实行（结论 PASSED、理由=待药师审方），
--                       M06 回执（V800 id 53/54）补 PHARMACIST 行（通过/驳回+药师意见）
--   ② order_status_log  医嘱状态迁移日志——只增：状态机每次迁移留痕（OrderStateMachineService
--                       回接落库），重整/撤回/停嘱原因在此留痕（04 Spec §4 表注）
--   ③ medical_order 增列 oral_confirmed_at——抢救口头医嘱补录确认时点（oral-confirm 端点落值；
--                       本 PR 内 V904 表增列属合法演进，V904 文件本身禁改）
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删；
--   两表为流水型只增语义（对齐 billing.deposit_txn/insurance_call_log 先例：保留审计列与
--   deleted 列形态，业务面仅 INSERT 不作 UPDATE/DELETE）；updated_at 触发器照挂。

CREATE TABLE inpatient.order_audit (
    id              BIGINT       PRIMARY KEY,
    order_id        BIGINT       NOT NULL,                          -- 所属医嘱主键（medical_order 1:N order_audit）
    stage           VARCHAR(16)  NOT NULL,                          -- 审核阶段两值词表：SYSTEM 系统自动审核（开立后全员必经预检）/ PHARMACIST 药师审方（M06 回执，仅用药类）
    review_task_no  VARCHAR(64)  NULL,                              -- 审核方引用（M06 review_task 审方任务单号；SYSTEM 行为 NULL）
    conclusion      VARCHAR(16)  NOT NULL,                          -- 审核结论两值词表：PASSED 通过 / REJECTED 驳回（用药类 SYSTEM 预进行=PASSED，理由列声明待药师审）
    reason          VARCHAR(255) NULL,                              -- 审核理由（驳回时=药师意见必填；通过时可空或备注预检说明）
    audit_operator  VARCHAR(64)  NOT NULL,                          -- 审核操作者（SYSTEM 行=开立医生员工 ID；PHARMACIST 行=审方药师员工 ID——M06 回执载荷 auditOperator）
    occurred_at     TIMESTAMPTZ  NOT NULL,                          -- 审核发生时点（SYSTEM=系统预检时点；PHARMACIST=M06 回执 auditedAt）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.order_audit IS '住院医嘱审核流水（只增）：系统自动审核与药师审方双阶段结论留痕；stage 区分审核方，用药类 CREATED 停留期语义=待药师审（04 Spec 红线 2）';
CREATE INDEX idx_order_audit_order ON inpatient.order_audit (order_id, occurred_at) WHERE deleted = 0;
CREATE TRIGGER trg_order_audit_updated_at BEFORE UPDATE ON inpatient.order_audit
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.order_status_log (
    id           BIGINT       PRIMARY KEY,
    order_id     BIGINT       NOT NULL,                             -- 所属医嘱主键（medical_order 1:N order_status_log）
    from_status  VARCHAR(20)  NOT NULL,                             -- 迁移前状态（OrderStatus 八态；重整留痕行 from=to）
    to_status    VARCHAR(20)  NOT NULL,                             -- 迁移后状态（OrderStatus 八态；重整留痕行 from=to）
    reason       VARCHAR(255) NOT NULL,                             -- 迁移/留痕原因（审核通过/驳回/停嘱理由/作废理由/撤回/重整等）
    operator     VARCHAR(64)  NOT NULL,                             -- 操作者员工 ID（触发迁移/留痕的主体）
    occurred_at  TIMESTAMPTZ  NOT NULL,                             -- 发生时点（服务器时间——状态机留痕统一落应用服务器时钟，不取回执时点）
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.order_status_log IS '住院医嘱状态迁移日志（只增）：状态机每次迁移自动留痕；重整/撤回/停嘱原因留痕（from_status=to_status 表示无迁移动作留痕，如医嘱重整）';
CREATE INDEX idx_order_status_log_order ON inpatient.order_status_log (order_id, occurred_at) WHERE deleted = 0;
CREATE TRIGGER trg_order_status_log_updated_at BEFORE UPDATE ON inpatient.order_status_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- 抢救口头医嘱补录确认时点（Task 5 V904 oral_flag 标记位的确认收口列：护士复诵执行后据实补记，
--   oral-confirm 端点落值；限时催办归 P3——本 PR 仅落标记+确认时点）
ALTER TABLE inpatient.medical_order ADD COLUMN oral_confirmed_at TIMESTAMPTZ NULL;
COMMENT ON COLUMN inpatient.medical_order.oral_confirmed_at IS '抢救口头医嘱补录确认时点（oral_flag 明细行标记的确认收口：oral-confirm 端点落值=服务器时间；未确认为 NULL，重复确认被应用层拦截）';
