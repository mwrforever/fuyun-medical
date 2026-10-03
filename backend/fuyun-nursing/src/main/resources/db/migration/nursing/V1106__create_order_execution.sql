-- V1106：M05 执行域三表（05-nursing Spec FU-M05-04/06；P2 PR-3 Task 2；台账已先记再改，通用段——
--   全局最大 V1105 的下一号，乱序守卫通过）。
--   ① order_execution       医嘱执行单（三路生成：转抄临时/计划批量/摆药挂接；状态机与回签对账载体）
--   ② execution_check_log   扫码核对流水（只增；腕带/袋签/设备三扫 + 破码放行留痕，四端点族审计底座）
--   ③ infusion_monitor_link 输液监测挂接（袋签↔执行单↔IoT 设备关联，M14 告警联动锚）
-- 实测裁决：nursing_task.source_ref 列 V805 已在位（含执行单号语义），Task 12 联动幂等直接使用
--   既有列，本迁移不追加 ALTER（brief 条件分支「若已有该列则不追加」命中）。
-- 登记义务：本迁移为纯表结构无事件登记；执行回签/输注起止事件登记面为 V800 id 61–64（P1 冻结）。
-- 通用约定（V801/V905 同款）：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、
--   逻辑删、状态 VARCHAR 常量、普通表挂 public.fuyun_set_updated_at 触发器。

CREATE TABLE nursing.order_execution (
    id                BIGINT       PRIMARY KEY,
    execution_no      VARCHAR(32)  NOT NULL,                             -- 执行单号（EX+yyyyMMdd+5 位流水，本模块签发）
    m04_order_no      VARCHAR(32)  NOT NULL,                             -- M04 医嘱号（生成来源医嘱引用）
    m04_plan_no       VARCHAR(32)  NULL,                                 -- M04 医嘱计划号（长期医嘱计划拆分引用；临时医嘱单次执行单为 NULL）
    visit_id          VARCHAR(14)  NOT NULL,                             -- 住院就诊号（I 型 14 位，签发主体 M04）
    patient_id        BIGINT       NOT NULL,                             -- 患者 ID
    ward_id           VARCHAR(64)  NOT NULL,                             -- 病区编码（与 nursing_ward_patient/任务表同宽口径）
    bed_no            VARCHAR(32)  NULL,                                 -- 床位号（冗余展示，转床随事件重定向）
    execution_type    VARCHAR(16)  NOT NULL,                             -- 执行类型：GENERIC 通用给药 / INFUSION 输液（输液类走监测挂接链）
    exec_item_code    VARCHAR(32)  NOT NULL,                             -- 执行项目编码（医嘱项目引用）
    exec_item_name    VARCHAR(128) NOT NULL,                             -- 执行项目名称（冗余展示）
    dosage_text       VARCHAR(255) NULL,                                 -- 用法用量文本（医嘱转抄快照，执行提示用）
    plan_time         TIMESTAMPTZ  NOT NULL,                             -- 计划执行时间（逾期判定与时间窗排序基准）
    status            VARCHAR(16)  NOT NULL DEFAULT 'CREATED',           -- 状态机六态：CREATED 已生成 / SIGNED 已签收 / CHECKED 已核对 / EXECUTING 执行中 / COMPLETED 已完成 / CANCELLED 已撤销
    signed_at         TIMESTAMPTZ  NULL,                                 -- 签收时点（环节时点集：签收动作落值）
    checked_at        TIMESTAMPTZ  NULL,                                 -- 核对通过时点（环节时点集：扫码核对 PASS 落值）
    started_at        TIMESTAMPTZ  NULL,                                 -- 开始执行时点（环节时点集；输液类=开始输注）
    finished_at       TIMESTAMPTZ  NULL,                                 -- 执行完成时点（环节时点集；输液类=输注结束）
    needle_out_at     TIMESTAMPTZ  NULL,                                 -- 拔针时点（输液类专属环节时点；通用类为 NULL）
    executor_id       BIGINT       NULL,                                 -- 执行护士员工 ID（签收/执行动作主体；生成时未定为 NULL）
    checker_id        BIGINT       NULL,                                 -- 核对护士员工 ID（扫码核对动作主体）
    override_flag     BOOLEAN      NOT NULL DEFAULT false,               -- 破码放行标识（true=经授权破码跳过常规核对，授权角色见 ward_config.override_roles）
    cancel_reason     VARCHAR(255) NULL,                                 -- 撤销原因（医嘱停止/作废/出院清理联动撤销时必填）
    confirm_status    VARCHAR(16)  NOT NULL DEFAULT 'PENDING',           -- 回签对账状态：PENDING 待对账 / COMPENSATING 补偿中（主路径未达经补偿端口重试）/ CONFIRMED 已对账
    escalation_count  INT          NOT NULL DEFAULT 0,                   -- 升级次数（逾期升级动作递增，与任务表口径同源）
    latest_alarm_no   VARCHAR(32)  NULL,                                 -- 最新关联告警号（IoT 输液告急挂接锚，M14 告警号）
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted           SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.order_execution IS '医嘱执行单（住院医嘱执行载体：三路生成/扫码核对/双路回签对账；输液类联动监测挂接与 IoT 告警）';
CREATE UNIQUE INDEX uk_execution_no ON nursing.order_execution (execution_no) WHERE deleted = 0;
-- 单计划一执行单：同医嘱同计划至多一张在册执行单（长期医嘱防重生成锚；临时医嘱 m04_plan_no 为 NULL 不受约束）
CREATE UNIQUE INDEX uk_execution_plan ON nursing.order_execution (m04_order_no, m04_plan_no) WHERE deleted = 0;
CREATE INDEX idx_execution_ward_status_time ON nursing.order_execution (ward_id, status, plan_time);
CREATE INDEX idx_execution_patient ON nursing.order_execution (patient_id);
CREATE INDEX idx_execution_confirm_status ON nursing.order_execution (confirm_status);
CREATE TRIGGER trg_order_execution_updated_at BEFORE UPDATE ON nursing.order_execution
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.execution_check_log (
    id            BIGINT      PRIMARY KEY,
    execution_no  VARCHAR(32) NOT NULL,                                  -- 执行单号（order_execution 引用；一次执行多轮核对留痕）
    check_type    VARCHAR(16) NOT NULL,                                  -- 核对方式：WRISTBAND 腕带扫描 / BAG_LABEL 袋签扫描 / DEVICE 设备绑定核对 / OVERRIDE 破码放行
    check_result  VARCHAR(8)  NOT NULL,                                  -- 核对结论：PASS 通过 / FAIL 失败
    fail_type     VARCHAR(32) NULL,                                      -- 失败类型五词表（check_result=FAIL 时必填）：WRISTBAND_MISMATCH 腕带不匹配 / BAG_MISMATCH 袋签不匹配 / DEVICE_MISMATCH 设备不匹配 / WRONG_PATIENT 患者不符 / OTHER 其他
    code_digest   VARCHAR(64) NOT NULL,                                  -- 扫码摘要（脱敏口径：前 4 后 2 明文+总长度，禁全文落库——条码原文属可回放敏感面）
    operator_id   BIGINT      NOT NULL,                                  -- 核对操作护士员工 ID（破码放行行=被授权放行者）
    occurred_at   TIMESTAMPTZ NOT NULL,                                  -- 核对发生时点（服务器时间）
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    VARCHAR(64) NOT NULL DEFAULT 'system',
    updated_by    VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted       SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.execution_check_log IS '扫码核对流水（只增）：腕带/袋签/设备三扫与破码放行全量留痕；条码摘要脱敏落库，等保敏感面';
CREATE INDEX idx_execution_check_no ON nursing.execution_check_log (execution_no, occurred_at) WHERE deleted = 0;
CREATE TRIGGER trg_execution_check_updated_at BEFORE UPDATE ON nursing.execution_check_log
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE nursing.infusion_monitor_link (
    id               BIGINT       PRIMARY KEY,
    execution_no     VARCHAR(32)  NOT NULL,                              -- 执行单号（一执行单一挂接；输液类执行单专属）
    bag_label_code   VARCHAR(64)  NOT NULL,                              -- 输液袋标签码（摆药贴签或护士站补录；M14 告警关联维度之一）
    iot_device_id    VARCHAR(64)  NULL,                                  -- IoT 设备标识（PDA 扫码回填；输液监控类设备，未绑定为 NULL）
    latest_alarm_no  VARCHAR(32)  NULL,                                  -- 最新告警号（escalation 挂单锚；随 M14 告警事件刷新）
    link_status      VARCHAR(16)  NOT NULL DEFAULT 'MONITORING',         -- 挂接状态：MONITORING 监测中 / ENDED 输注结束（拔针）/ RELEASED 已解除（异常释放）
    started_at       TIMESTAMPTZ  NOT NULL,                              -- 开始监测时点（开始输注动作联动）
    ended_at         TIMESTAMPTZ  NULL,                                  -- 结束监测时点（拔针/解除动作联动）
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted          SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE nursing.infusion_monitor_link IS '输液监测挂接（执行单↔袋签↔IoT 设备三元关联；M14 告警联动与拔针收口的唯一挂接面）';
CREATE UNIQUE INDEX uk_monitor_link_execution ON nursing.infusion_monitor_link (execution_no) WHERE deleted = 0;
CREATE TRIGGER trg_monitor_link_updated_at BEFORE UPDATE ON nursing.infusion_monitor_link
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
