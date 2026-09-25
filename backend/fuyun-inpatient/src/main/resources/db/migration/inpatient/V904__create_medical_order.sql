-- V904：M04 医嘱开立域三表（04-inpatient Spec §4/§3.3/§6；P2 PR-1 Task 5；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   ① medical_order        医嘱主表——开立即 CREATED（待审核），八态状态机迁移唯一经
--                          OrderStateMachineService（迁移表驱动 + CAS + 影响行数判定）；
--                          standby_flag 嘱托标记仅 LONG 医嘱可 true（应用层四层校验守卫，
--                          列默认 false 兜底）；成组医嘱 group_no 缺省由应用层填 order_no
--   ② medical_order_item   医嘱明细行——与主表同事务落库；name_snapshot 项目名称快照
--                          （计价/审方免回查）；fee_priced/fee_stopped 计费回执两列
--                          （M13 对账用：已计价/已截断，开立默认 false）
--   ③ order_frequency      用药频次专业字典——七行种子挂接 M01 medication.frequency
--                          字典条目（dict_code 逐字同 V607 dict_item.item_code），
--                          供长期医嘱频次校验（IP-1021）与计划拆分取数
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删、状态 VARCHAR 常量；
--   频次种子形态同构 V607 先例：INSERT ... WHERE NOT EXISTS 幂等 + 种子行取小整数 ID（本迁移占用
--   order_frequency 1–7，后续种子迁移续用未占小整数）。

CREATE TABLE inpatient.medical_order (
    id          BIGINT       PRIMARY KEY,
    order_no    VARCHAR(32)  NOT NULL,                           -- 医嘱号（MO+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("MO") 签发）
    visit_id    BIGINT       NOT NULL,                           -- 住院就诊主键（inpatient_visit.id 非 I 型号；转科停嘱查询键）
    patient_id  BIGINT       NOT NULL,                           -- 患者主索引（开立时点就诊行权威值，事件载荷同源）
    order_type  VARCHAR(16)  NOT NULL,                           -- 医嘱类型九值词表：DRUG 药品 / LAB 检验 / EXAM 检查 / SURGERY 手术 / BLOOD 用血 / NURSING 护理 / DIET 膳食 / CONSULT 会诊 / DISCHARGE_MED 出院带药（routing 子键小写映射见 OrderType）
    order_class VARCHAR(8)   NOT NULL,                           -- 医嘱分类两值词表：LONG 长期 / STAT 临时
    standby_flag BOOLEAN     NOT NULL DEFAULT false,             -- 备用嘱（嘱托）标记：仅 LONG 可 true（应用层校验拒 STAT+standby=IP-1022；列级默认 false 兜底，true 时由嘱托触发面消费）
    group_no    VARCHAR(32)  NOT NULL,                           -- 成组医嘱组号（单条医嘱=order_no 缺省回填；成组多行共用组号）
    freq_code   VARCHAR(16)  NULL,                               -- 频次编码（order_frequency.freq_code；长期医嘱非空、临时医嘱 NULL）
    begin_at    TIMESTAMPTZ  NULL,                               -- 医嘱生效起始时点（审核通过面写入，开立时缺省 NULL）
    end_at      TIMESTAMPTZ  NULL,                               -- 停嘱时点（STOPPED 迁移同语句补写=服务器时间；未停为 NULL）
    doctor_id   VARCHAR(64)  NOT NULL,                           -- 开立医生（M01 用户标识，与审计列口径统一）
    ordered_at  TIMESTAMPTZ  NOT NULL,                           -- 开立时点（应用服务器时钟）
    stop_reason VARCHAR(255) NULL,                               -- 停嘱原因（转科固定文案「转科」/医生停嘱理由）
    status      VARCHAR(20)  NOT NULL DEFAULT 'CREATED',         -- CREATED 已开立待审核 / AUDITED 审核通过待转抄 / AUDIT_REJECTED 审核驳回 / TRANSFERRED 已转抄 / EXECUTING 执行中 / COMPLETED 已完成（终态）/ CANCELLED 已作废（终态）/ STOPPED 已停嘱（终态）——八态词表，合法迁移表见 04 Spec §3.3
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted     SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.medical_order IS '住院医嘱主表：开立即 CREATED，八态状态机唯一经 OrderStateMachineService 迁移；成组医嘱共用 group_no；visit_id 为 inpatient_visit 主键引用';
CREATE UNIQUE INDEX uk_medical_order_no ON inpatient.medical_order (order_no) WHERE deleted = 0;
CREATE INDEX idx_medical_order_visit_status ON inpatient.medical_order (visit_id, status);
CREATE TRIGGER trg_medical_order_updated_at BEFORE UPDATE ON inpatient.medical_order
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.medical_order_item (
    id            BIGINT       PRIMARY KEY,
    order_id      BIGINT       NOT NULL,                         -- 所属医嘱主键（medical_order 1:N medical_order_item）
    item_seq      INT          NOT NULL,                         -- 行序号（同一医嘱内 1 起递增；成组医嘱组内序号）
    continue_flag BOOLEAN      NOT NULL DEFAULT false,           -- 延续标志（成组医嘱组内延续执行标记：组内前行未完时后行延续）
    item_type     VARCHAR(16)  NOT NULL,                         -- 行项目类型（与 order_type 同词表形态：DRUG 药品 / LAB 检验 / EXAM 检查等；过敏拦截仅判 DRUG）
    item_code     VARCHAR(64)  NOT NULL,                         -- 项目编码（药品/检验/检查等项目字典编码）
    name_snapshot VARCHAR(255) NOT NULL,                         -- 项目名称快照（开立时点誊写；计价/审方免回查；可入事件载荷——药品通用名非敏感项）
    dosage        VARCHAR(32)  NULL,                             -- 剂量（数值字符串，如 0.5；药品项必填——IP-1011 校验面）
    dosage_unit   VARCHAR(16)  NULL,                             -- 剂量单位（如 g/ml；药品项必填——IP-1011 校验面）
    route         VARCHAR(16)  NULL,                             -- 给药途径（M01 medication.frequency 同族字典 medication.route 的 code；药品项必填——IP-1011 校验面）
    drip_rate     VARCHAR(32)  NULL,                             -- 滴速（如 40 滴/分；静滴类医嘱携带）
    quantity      DECIMAL(12,2) NOT NULL,                        -- 数量（正数；事件载荷 quantity 以 DECIMAL string 承载）
    exec_dept_id  VARCHAR(64)  NULL,                             -- 执行科室编码（M01 组织机构 code；LIS/PACS 等执行归口）
    skin_test_flag BOOLEAN     NOT NULL DEFAULT false,           -- 皮试标记（药品项：执行前须皮试）
    oral_flag     BOOLEAN      NOT NULL DEFAULT false,           -- 抢救口头医嘱补录标记（口头医嘱执行后补录确认，Task 6 oral-confirm 消费）
    fee_priced    BOOLEAN      NOT NULL DEFAULT false,           -- 计费回执标记：M13 已计价（对账用，默认 false；billing 回执面刷新）
    fee_stopped   BOOLEAN      NOT NULL DEFAULT false,           -- 计费截断回执标记：M13 已按停嘱时点截断计费（对账用，默认 false）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.medical_order_item IS '住院医嘱明细行：与主表同事务落库；行序号组内递增；计费回执两列（fee_priced/fee_stopped）为 M13 停嘱对账面';
CREATE INDEX idx_medical_order_item_order ON inpatient.medical_order_item (order_id);
CREATE TRIGGER trg_medical_order_item_updated_at BEFORE UPDATE ON inpatient.medical_order_item
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.order_frequency (
    id            BIGINT       PRIMARY KEY,
    freq_code     VARCHAR(16)  NOT NULL,                         -- 频次编码（行业惯例缩写：qd/bid/tid/qid/qn/prn/st；长期医嘱 freq_code 校验键——IP-1021）
    freq_name     VARCHAR(32)  NOT NULL,                         -- 频次名称（与 V607 medication.frequency 条目 item_name 同源）
    times_per_day INT          NOT NULL,                         -- 每日次数（qd=1/bid=2/tid=3/qid=4/qn=1；prn/st 必要时与即刻语义无固定次数=0）
    time_points   VARCHAR(128) NULL,                             -- 执行时点序列（HH:mm 逗号分隔，如 08:00,16:00；prn/st 无固定时点为 NULL；计划拆分取数面）
    week_pattern  VARCHAR(32)  NULL,                             -- 周模式（如周一三五=MWF；本批种子均为 NULL，隔日/周模式频次扩充时启用）
    prn_flag      BOOLEAN      NOT NULL DEFAULT false,           -- 必要时（pro re nata）标记：true=按需执行不经计划拆分、由嘱托触发面消费
    dict_code     VARCHAR(16)  NOT NULL,                         -- 挂接 M01 medication.frequency 字典条目 item_code（V607 dict_version_id=2 条目逐字同源；两列分工：freq_code 为本域专业字典自有编码可扩充，dict_code 为 M01 字典对照锚）
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.order_frequency IS '住院用药频次专业字典：长期医嘱频次校验与计划拆分取数源；dict_code 挂接 M01 medication.frequency 字典条目';
CREATE UNIQUE INDEX uk_order_frequency_code ON inpatient.order_frequency (freq_code) WHERE deleted = 0;
CREATE TRIGGER trg_order_frequency_updated_at BEFORE UPDATE ON inpatient.order_frequency
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ---------------------------------------------------------------- 频次种子（七行，幂等 INSERT WHERE NOT EXISTS）
-- 种子 ID 取小整数 1–7（V607 先例：跨表 ID 空间独立，雪花 ID 19 位量级永不冲突）；
--   freq_code/dict_code/名称逐字同 V607 medication.frequency 对应条目（qd/bid/tid/qid/qn/prn/st 七条子集，
--   qod/q8h/q12h 三条本批不种子——本域消费面未及，扩充时续号 8+ 补种）。

-- 每日一次（quaque die，08:00 执行）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 1, 'qd', '每日一次', 1, '08:00', NULL, false, 'qd'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'qd' AND deleted = 0);

-- 每日两次（bis die，08:00/16:00 执行）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 2, 'bid', '每日两次', 2, '08:00,16:00', NULL, false, 'bid'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'bid' AND deleted = 0);

-- 每日三次（ter die，08:00/12:00/16:00 执行）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 3, 'tid', '每日三次', 3, '08:00,12:00,16:00', NULL, false, 'tid'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'tid' AND deleted = 0);

-- 每日四次（quater die，08:00/12:00/16:00/20:00 执行）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 4, 'qid', '每日四次', 4, '08:00,12:00,16:00,20:00', NULL, false, 'qid'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'qid' AND deleted = 0);

-- 每晚一次（quaque nocte，20:00 执行）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 5, 'qn', '每晚一次', 1, '20:00', NULL, false, 'qn'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'qn' AND deleted = 0);

-- 必要时（pro re nata，无固定次数与时点——prn_flag 标记按需执行，不经计划拆分）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 6, 'prn', '必要时', 0, NULL, NULL, true, 'prn'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'prn' AND deleted = 0);

-- 立即（statim，临时即刻 STAT 类——无固定次数与时点，单次即刻执行）
INSERT INTO inpatient.order_frequency (id, freq_code, freq_name, times_per_day, time_points, week_pattern, prn_flag, dict_code)
SELECT 7, 'st', '立即', 0, NULL, NULL, false, 'st'
WHERE NOT EXISTS (SELECT 1 FROM inpatient.order_frequency WHERE freq_code = 'st' AND deleted = 0);

-- 种子合计 7 行（qd/bid/tid/qid/qn/prn/st）；行数断言由模块单测 OrderFrequencyMapper 消费面承载。
