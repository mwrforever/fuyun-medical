-- V202：分诊台与候诊队列两表（M03 Spec §4/§6/§7，Task 7）：triage_record（分诊动作留痕——报到/
-- 二次分诊/调级/跨队列转接四类动作全留痕，只增语义）、queue_ticket（候诊票据——P1 诊区队列口径
-- queue_id=dept_code（偏差⑧），priority_score 冻结公式（类别分取最高单项+老幼残跨类叠加+封顶 999，
-- 偏差⑨经 2026-09-20 用户裁决细化），同分排序以 queue_time 数据库时间戳为权威、禁应用服务器时钟）。
-- 公共约定同 V200/V201 形态（雪花 ID / 数据库维护审计列 / 逻辑删 / 无外键 / 部分唯一索引 / 状态列
-- 值域=枚举 code 且词表入列注释）；资金无涉红线（裁决 7）：两表零金额列。
-- 候诊队列权威面：Redis ZSET（fy:outpatient:queue:{deptCode}）仅为加速视图，queue_ticket 的
-- WAITING 行为重启恢复权威（叫号服务重启后队列从排队表完整恢复，Spec :210）。

-- ---------------------------------------------------------------- 分诊动作留痕表
-- 分诊台四类动作全留痕（CHECK_IN 报到/RE_TRIAGE 二次分诊定医生/LEVEL_ADJUST 调级/QUEUE_TRANSFER
-- 跨队列转接）：只增语义，零更新（业务纠错以新动作行表达）；priority_factor 为急/老幼残/回诊因子
-- JSON（老幼残跨类叠加分依据，词表 ELDERLY/CHILD/DISABLED；绿通 900 为声明值 P1 恒不触发）。
CREATE TABLE outpatient.triage_record (
    id              BIGINT       PRIMARY KEY,                     -- 雪花 ID（MP ASSIGN_ID）
    visit_id        VARCHAR(14)  NOT NULL,                        -- 就诊号（uk_visit_id 同源）
    station_id      VARCHAR(64)  NOT NULL,                        -- 分诊台/自助终端标识（报到发起端）
    action          VARCHAR(16)  NOT NULL,                        -- 动作：CHECK_IN 报到/RE_TRIAGE 二次分诊/LEVEL_ADJUST 调级/QUEUE_TRANSFER 转队列
    triage_level    INT          NULL,                            -- 急诊分级快照（Ⅰ~Ⅳ=1~4，动作时刻值）
    target_queue    VARCHAR(64)  NOT NULL,                        -- 动作目标队列（=dept_code 诊区队列，偏差⑧）
    doctor_id       VARCHAR(64)  NULL,                            -- 二次分诊定医生（RE_TRIAGE 动作写入）
    priority_factor VARCHAR(255) NULL,                            -- 优先级因子 JSON（如 ["ELDERLY"]，词表 ELDERLY/CHILD/DISABLED）
    nurse_id        VARCHAR(64)  NOT NULL,                        -- 分诊护士操作者（OperatorContextHolder）
    reason          VARCHAR(255) NULL,                            -- 动作理由（调级/转队列留痕）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

-- 按就诊号回放分诊轨迹（分诊台调级/转队列纠错与审计回溯维度）
CREATE INDEX idx_triage_record_visit ON outpatient.triage_record (visit_id);

CREATE TRIGGER trg_triage_record_updated_at BEFORE UPDATE ON outpatient.triage_record
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 候诊票据表
-- 候诊队列权威行（queue_id=dept_code 诊区队列口径，偏差⑧）：priority_score 冻结公式
-- priority_score = min(999, 100 基础分 + max(绿通 900〔声明值 P1 恒不触发〕, 急诊分级 1/2/3/4→
-- 800/700/600/500, 回诊/复诊 300) + 老幼残 200)——类别分取最高单项不叠加、老幼残跨类叠加、
-- 封顶 999（防 ZSET score 编码位溢出）；ZSET score 编码 = priority_score*1e8 + queue_seq
-- （queue_seq 建行事务内当日序，与 queue_time 建行时序一致，同分排序权威=queue_time 库端时间戳）。
CREATE TABLE outpatient.queue_ticket (
    id             BIGINT       PRIMARY KEY,                      -- 雪花 ID（MP ASSIGN_ID）
    visit_id       VARCHAR(14)  NOT NULL,                         -- 就诊号（uk_visit_id 同源）
    queue_id       VARCHAR(64)  NOT NULL,                         -- 队列标识（=dept_code 诊区队列，P1 口径偏差⑧）
    ticket_no      VARCHAR(16)  NOT NULL,                         -- 票号（队列内当日序号，A+%03d 如 A007）
    ticket_type    VARCHAR(16)  NOT NULL,                         -- 票别：FIRST 初诊/VISIT 就诊/RETURN 回诊复诊/EXTRA 加号（P1 报到按 is_revisit 派生 FIRST/RETURN）
    doctor_id      VARCHAR(64)  NULL,                             -- 指派医生（二次分诊定医生；NULL=未指派任一医生可叫）
    priority_score INT          NOT NULL CHECK (priority_score >= 0 AND priority_score <= 999), -- 优先级分（冻结公式，封顶 999）
    queue_seq      INT          NOT NULL,                         -- 队列当日序（建行事务内 Redis INCR，单调递增与 queue_time 时序一致）
    queue_time     TIMESTAMPTZ  NOT NULL DEFAULT now(),           -- 建行时间（库端 now()，同分排序权威，禁应用时钟）
    called_count   INT          NOT NULL DEFAULT 0,               -- 叫号次数（重复叫号累加）
    call_time      TIMESTAMPTZ  NULL,                             -- 最近叫号时间（casCall 库端 now() 回填）
    serve_time     TIMESTAMPTZ  NULL,                             -- 接诊时间（Task 8 admit 联动 CALLED→SERVING 回填）
    status         VARCHAR(16)  NOT NULL DEFAULT 'WAITING',       -- WAITING 候诊/CALLED 已叫/SERVING 就诊中（Task 8 admit）/SERVED 诊毕（Task 8）/PASSED 过号再入/CANCELLED 已取消
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted        SMALLINT     NOT NULL DEFAULT 0
);

-- 就诊号+队列+当日序唯一锚（同就诊同队列重复建票防线；queue_id 入键——queue_seq 按队列独立签发，
-- 跨队列转接的新队列新票 seq 从 1 起与本队列既有票自然共存，同队列重复建票仍被拒；
-- fix round 1 Important-1：原 (visit_id, queue_seq) 两列键使「DEP001 报到→转 DEP002」新票
-- (visit, seq=1) 撞旧票行致 DuplicateKey 500，且旧注释对本索引失实，一并订正）
CREATE UNIQUE INDEX uk_ticket_visit ON outpatient.queue_ticket (visit_id, queue_id, queue_seq);

-- 队列内票号唯一锚（逻辑删行不占用唯一性）
CREATE UNIQUE INDEX uk_ticket_queue ON outpatient.queue_ticket (queue_id, ticket_no) WHERE deleted = 0;

-- 叫号惰性重建查询（selectWaiting：队列当日 WAITING 权威行整体读取，Spec :210 重启恢复）
CREATE INDEX idx_ticket_queue_status ON outpatient.queue_ticket (queue_id, status) WHERE deleted = 0;

CREATE TRIGGER trg_queue_ticket_updated_at BEFORE UPDATE ON outpatient.queue_ticket
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
