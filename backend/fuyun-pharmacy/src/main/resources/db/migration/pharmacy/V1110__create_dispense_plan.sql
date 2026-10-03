-- V1110：M06 住院摆药计划表 + 调剂单住院扩列（P2 PR-3 Task 3；台账 docs/migrations/flyway-version-registry.md
--   已先记再改——V1106–V1111 排定登记于 2026-10-01 立项条目）。
-- 号段口径：pharmacy 固定段 V700–V799 外走 V500+ 通用段（V1000 先例）；本号 V1110 大于基线全局
--   最大 V1105（origin/dev）亦大于本分支 V1109，乱序守卫通过。
-- 业务定位：dispense_plan 为住院医嘱→摆药计划载体（M06 药师摆药工作台底座），dispense 三列扩容
--   承接「摆药计划↔调剂单」关联；M05 签收衔接消费 delivered_at/received_by（病区签收时点与人）。
-- 登记义务：本迁移纯表结构无事件登记；id 28 载荷契约演进归 V1111。
-- 通用约定（V703/V1000 同款）：雪花 BIGINT 主键（MP ASSIGN_ID，禁 BIGSERIAL）、审计五列、
--   TIMESTAMPTZ 服务器时间（禁裸 TIMESTAMP——plan_time 给药时点/issued_at/delivered_at 同口径）、
--   逻辑删、状态 VARCHAR 常量、普通表挂 public.fuyun_set_updated_at 触发器。
-- 列宽实测：ward_id 取 VARCHAR(64)——pharmacy 域既有表无 ward_id 列，对齐全仓 nursing/inpatient/
--   billing 三域 ward_id 64 惯例（V1106/V903/V1001 同宽口径）。

CREATE TABLE pharmacy.dispense_plan (
    id              BIGINT       PRIMARY KEY,
    plan_no         VARCHAR(32)  NOT NULL,           -- 摆药计划号（DP+yyyyMMdd+流水，本模块签发）
    m04_order_no    VARCHAR(32)  NOT NULL,           -- 住院医嘱号（M04 medical_order.order_no；生成来源医嘱引用）
    visit_id        VARCHAR(14)  NOT NULL,           -- 住院就诊号（I 型 14 位，M02 结构规范）
    patient_id      BIGINT       NOT NULL,           -- 患者主索引（M02）
    ward_id         VARCHAR(64)  NOT NULL,           -- 病区编码（目标病区；全仓 ward_id 64 同宽口径）
    plan_type       VARCHAR(16)  NOT NULL,           -- 计划类型：SINGLE_DOSE 单剂量 / PIVAS 静配 / WHOLE 整包
    plan_time       TIMESTAMPTZ  NOT NULL,           -- 给药时点（排程基准；同医嘱同给药时点一计划的唯一维度）
    status          VARCHAR(16)  NOT NULL DEFAULT 'CREATED', -- CREATED/PICKING/PICKED/CHECKED/DELIVERED/CANCELLED——住院链止于 DELIVERED 签收（与门诊 ISSUED 语义区分，住院计划不走 ISSUED）
    pivas_batch_no  VARCHAR(32)  NULL,               -- 排批号（PIVAS 给药时间分批批次号；非 PIVAS 计划为 NULL）
    label_printed   BOOLEAN      NOT NULL DEFAULT false, -- 贴签核对标记（false=未打印贴签；打印降级时置位留注记）
    picked_by       BIGINT       NULL,               -- 摆药师员工 ID（摆药动作主体；生成时未定为 NULL）
    verified_by     BIGINT       NULL,               -- 核对药师员工 ID（双人核对第二签；核对通过回写）
    issued_at       TIMESTAMPTZ  NULL,               -- 出库时点（摆药完成交付病区/静配前时点）
    delivered_at    TIMESTAMPTZ  NULL,               -- 病区签收时点（住院链终态动作时点，M05 签收衔接消费）
    received_by     BIGINT       NULL,               -- 病区签收人员工 ID（M05 签收衔接动作主体）
    cancel_reason   VARCHAR(255) NULL,               -- 撤销原因（医嘱停止/作废联动撤销时必填）
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

-- 计划号唯一（业务号锚；部分唯一索引随逻辑删口径，V703 同款）
CREATE UNIQUE INDEX uk_dispense_plan_no ON pharmacy.dispense_plan (plan_no) WHERE deleted = 0;
-- 病区工作台高频过滤（病区+状态列表）
CREATE INDEX idx_dispense_plan_ward_status ON pharmacy.dispense_plan (ward_id, status) WHERE deleted = 0;
-- 同医嘱同给药时点一计划（排程防重锚；m04_order_no 单列检索由本索引前导列承载，不另建单列索引）
CREATE UNIQUE INDEX uk_dispense_plan_order_time ON pharmacy.dispense_plan (m04_order_no, plan_time) WHERE deleted = 0;

CREATE TRIGGER trg_dispense_plan_updated_at BEFORE UPDATE ON pharmacy.dispense_plan
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

-- ===================== 二、dispense 住院扩列（四可空——门诊行 NULL，住院行必填由应用层校验） =====================
ALTER TABLE pharmacy.dispense ADD COLUMN ward_id          VARCHAR(64); -- 目标病区编码（住院摆药行归属病区；门诊行 NULL；与本表 ward_id 64 同宽口径）
ALTER TABLE pharmacy.dispense ADD COLUMN m04_order_no     VARCHAR(32); -- 住院医嘱号（住院行回链 M04 医嘱；门诊行 NULL）
ALTER TABLE pharmacy.dispense ADD COLUMN dispense_plan_no VARCHAR(32); -- 摆药计划号（住院行回链 dispense_plan.plan_no；门诊行 NULL）
-- visit_id 列 V703 已在位不追加：门诊 O 型复用为住院 I 型 string 承载，列注释双语义声明如下
COMMENT ON COLUMN pharmacy.dispense.visit_id IS '就诊号双语义承载：门诊行 O 型就诊号 / 住院行 I 型 14 位住院就诊号（V1110 住院摆药扩列声明，列本体 V703 已在位）';
