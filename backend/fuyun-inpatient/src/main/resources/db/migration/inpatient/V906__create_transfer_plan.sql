-- V906：M04 转抄与执行计划域两表（04-inpatient Spec §4/§6 FU-M04-06 上；P2 PR-1 Task 7；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   ① order_transfer_log  转抄记录（只增）——转抄双人核对留痕（04 Spec §4 表注：转抄护士/
--                        转抄时间/核对结论/第二核对人，高危/输血强制第二核对人——调研依据 5）；
--                        每次 AUDITED→TRANSFERRED 迁移落一行
--   ② order_execute_plan 执行计划——医嘱执行闭环的计划实例面（M05 执行单对齐）：
--                        临时医嘱转抄时同步生成单次计划；长期医嘱日切分解（归后续任务）与
--                        嘱托按需触发共用本表；uk_plan_order_item_time（order_item_id+plan_time）
--                        为日切分解幂等兜底（同项同时点不重开计划——M13 唯一键不重复计价的
--                        计划侧对偶面）
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删；
--   order_transfer_log 为流水型只增语义（对齐 V905 两表先例：保留审计列与 deleted 列形态，
--   业务面仅 INSERT 不作 UPDATE/DELETE）；updated_at 触发器照挂。

CREATE TABLE inpatient.order_transfer_log (
    id                BIGINT       PRIMARY KEY,
    order_id          BIGINT       NOT NULL,                        -- 所属医嘱主键（medical_order 1:N order_transfer_log）
    transfer_nurse    VARCHAR(64)  NOT NULL,                        -- 转抄护士（员工 ID string；双人核对的执行转抄方签名）
    transferred_at    TIMESTAMPTZ  NOT NULL,                        -- 转抄时点（服务器时间；同步作 transferred 事件 firstTransferredAt 载荷源）
    conclusion        VARCHAR(16)  NOT NULL,                        -- 核对结论两值词表：PASSED 核对通过（三查七对相符）/ REJECTED 核对不符（未转抄，临床退回医生站处理——应用层拦截不入本表，词表保完整）
    second_checker_id VARCHAR(64)  NULL,                            -- 第二核对人（员工 ID string；双人核对签名——高危药/输血类医嘱强制非空，应用层 IP-1016 校验面）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.order_transfer_log IS '住院医嘱转抄记录（只增）：AUDITED→TRANSFERRED 每次迁移一行，双人核对留痕（转抄护士/时间/结论/第二核对人，高危/输血强制第二核对人）';
CREATE INDEX idx_order_transfer_log_order ON inpatient.order_transfer_log (order_id, transferred_at) WHERE deleted = 0;
CREATE TRIGGER trg_order_transfer_log_updated_at BEFORE UPDATE ON inpatient.order_transfer_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.order_execute_plan (
    id                 BIGINT       PRIMARY KEY,
    plan_no            VARCHAR(32)  NOT NULL,                       -- 计划号（PL+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("PL") 签发）
    order_id           BIGINT       NOT NULL,                       -- 所属医嘱主键（medical_order 1:N order_execute_plan——经医嘱行取 order_class 分野转科三分口径）
    order_item_id      BIGINT       NOT NULL,                       -- 所属医嘱明细行主键（medical_order_item.id；计划按明细行粒度开立——执行回签/计价按项对齐）
    visit_id           BIGINT       NOT NULL,                       -- 住院就诊主键（inpatient_visit.id；M05 执行单与转科重定向查询键）
    ward_id            VARCHAR(64)  NOT NULL,                       -- 执行病区编码（生成时点患者所在病区 inpatient_visit.current_ward_id；转科时临时 PENDING 计划重定向本列）
    plan_time          TIMESTAMPTZ  NOT NULL,                       -- 计划执行时点（临时单次计划=转抄时点+默认准备窗口；长期分解=频次时点；嘱托触发=触发时点+默认窗口）
    shift              VARCHAR(16)  NOT NULL,                       -- 班次三值词表（照 nursing V801 病区班次定义先例）：DAY 白班（08:00–16:00）/ EVENING 小夜班（16:00–24:00）/ NIGHT 大夜班（00:00–08:00）——按 plan_time 落班，窗口左闭右开
    executor_id        VARCHAR(64)  NULL,                           -- 执行护士（员工 ID string；执行回签时落值——W-33 id 55 契约的存储落点，未执行为 NULL）
    executed_at        TIMESTAMPTZ  NULL,                           -- 执行时点（执行回签时落值=回签服务器时间或回执时点；未执行为 NULL）
    route_check_result VARCHAR(255) NULL,                           -- 给药途径核对结论（执行回签可选携带；静滴/口服等途径核对留痕，未回签为 NULL）
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',     -- 计划状态三值词表：PENDING 待执行 / EXECUTED 已执行（回签迁移，医嘱头联动推进）/ CANCELLED 已作废（停嘱联动未来计划作废、转科长期计划作废）
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted            SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.order_execute_plan IS '住院医嘱执行计划：临时单次计划（转抄同步生成）/长期日切分解/嘱托按需触发三源共用；uk_plan_order_item_time 为日切分解幂等兜底（同项同时点不重开，M13 不重复计价的计划侧对偶面）';
CREATE UNIQUE INDEX uk_order_execute_plan_no ON inpatient.order_execute_plan (plan_no) WHERE deleted = 0;
CREATE UNIQUE INDEX uk_plan_order_item_time ON inpatient.order_execute_plan (order_item_id, plan_time) WHERE deleted = 0;
CREATE INDEX idx_order_execute_plan_ward_time ON inpatient.order_execute_plan (ward_id, plan_time) WHERE deleted = 0;
CREATE INDEX idx_order_execute_plan_order ON inpatient.order_execute_plan (order_id) WHERE deleted = 0;
CREATE TRIGGER trg_order_execute_plan_updated_at BEFORE UPDATE ON inpatient.order_execute_plan
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
