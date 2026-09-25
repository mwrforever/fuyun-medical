-- V907：M04 出院管理域两表（04-inpatient Spec §4/§5/§6 FU-M04-07；P2 PR-1 Task 9；
--   台账 docs/migrations/flyway-version-registry.md 已先记再改）。
--   ① discharge_request 出院申请——「预出院/明日出院」实践载体（调研依据 11）：
--      出院申请（visit→DISCHARGE_REQUESTED）单事务完成在途清理编排（长期医嘱批量停嘱/
--      临时医嘱追踪清单/未执行计划作废——清理结果入本表 clearance_result）与费用预审
--      （结清→READY / 欠费→BLOCKED 附欠费额快照）；uk_visit_active 部分唯一兜底
--      「一 visit 至多一条在途申请」（并发重复申请 DB 硬防线）；
--      结算完成标记（settlement_completed_at）与挂账审批单号（approval_no）由
--      billing.settlement.completed / billing.arrears.approved 消费驱动落值
--   ② follow_up_plan 随访计划——出院医嘱三要素之一（注意事项/带药/随访，调研依据 11）：
--      离院确认时按「出院后 N 日」参数生成；触达经 M01 通知中心（通知中心缺位期降级为
--      工作站列表可见——五大降级清单②）
-- 通用约定：雪花 BIGINT 主键（MP ASSIGN_ID）、审计五列、TIMESTAMPTZ 服务器时间、逻辑删；
--   clearance_result 为 JSONB 快照列（写侧 JsonbStringTypeHandler 以 jsonb 类型参数落库，
--   nursing V806/V807 先例）；金额 BIGINT 分值制（GC18——仅存 billing 权威数据快照回显，
--   请求面零金额输入）；updated_at 触发器照挂（照 V602/V200 形态）。

CREATE TABLE inpatient.discharge_request (
    id                       BIGINT       PRIMARY KEY,
    request_no               VARCHAR(32)  NOT NULL,                        -- 出院申请单号（DC+yyyyMMdd+5 位流水，InpatientSeqGate.nextNo("DC") 签发）
    visit_id                 BIGINT       NOT NULL,                        -- 住院就诊主键（inpatient_visit.id——与 medical_order.visit_id 同口径，禁 I 型号入库）
    patient_id               BIGINT       NOT NULL,                        -- 患者主索引（随访计划与事件载荷取数面）
    requester_id             VARCHAR(64)  NOT NULL,                        -- 申请医生（员工 ID string；出院校验执业面归 M01，本表仅留痕）
    requested_at             TIMESTAMPTZ  NOT NULL,                        -- 申请时点（服务器时钟）
    expect_discharge_at      TIMESTAMPTZ  NULL,                            -- 预出院时间（「明日出院」预出院模式载体，调研依据 11）
    discharge_way            VARCHAR(16)  NOT NULL,                        -- 离院方式（病案首页代码词表：1 医嘱离院 / 2 医嘱转院 / 3 转社区卫生服务机构/乡镇卫生院 / 4 非医嘱离院 / 5 死亡 / 9 其他；离院确认时誊写至 inpatient_visit.discharge_way）
    clearance_result         JSONB        NOT NULL,                        -- 在途清理结果快照 {stoppedLongCount:长期停嘱数, trackedOrders:[{orderNo,orderClass,status}]临时追踪清单, cancelledPlanCount:计划作废数}——人工处置面（无合法停嘱边医嘱）据此追踪
    arrears_amount           BIGINT       NULL,                            -- 欠费额快照（分；预审 BLOCKED 时=max(0,未结清合计-押金余额)，READY 为 NULL——billing 权威数据的回显，DTO/请求面零金额输入 GC18）
    settlement_completed_at  TIMESTAMPTZ  NULL,                            -- 出院结算完成时点（消费 billing.settlement.completed 落标记；离院确认双条件之一，重复消费幂等）
    approval_no              VARCHAR(64)  NULL,                            -- 挂账审批单号（消费 billing.arrears.approved 由 BLOCKED→READY 时记录——放行凭证留痕）
    status                   VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',   -- REQUESTED 已申请（在途清理与费用预审中） / READY 预审通过待结算离院 / BLOCKED 预审未通过欠费挂账审批中 / COMPLETED 离院完成（终态） / CANCELLED 取消出院（终态，visit 回 ADMITTED 且医嘱不复活）
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by               VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by               VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted                  SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.discharge_request IS '出院申请：预出院模式载体——单事务在途清理编排（长期停嘱/临时追踪/计划作废）+费用预审快照；结算完成标记与挂账审批放行由 billing 事件消费驱动';
CREATE UNIQUE INDEX uk_discharge_request_no ON inpatient.discharge_request (request_no) WHERE deleted = 0;
CREATE UNIQUE INDEX uk_visit_active ON inpatient.discharge_request (visit_id) WHERE status IN ('REQUESTED','READY','BLOCKED') AND deleted = 0;
CREATE INDEX idx_discharge_request_visit ON inpatient.discharge_request (visit_id) WHERE deleted = 0;
CREATE TRIGGER trg_discharge_request_updated_at BEFORE UPDATE ON inpatient.discharge_request
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();

CREATE TABLE inpatient.follow_up_plan (
    id           BIGINT       PRIMARY KEY,
    visit_id     BIGINT       NOT NULL,                                   -- 住院就诊主键（inpatient_visit.id；出院就诊轴）
    patient_id   BIGINT       NOT NULL,                                   -- 患者主索引（触达对象定位）
    plan_date    DATE         NOT NULL,                                   -- 随访日期（出院日后 N 日——离院确认时按请求参数生成）
    way          VARCHAR(16)  NOT NULL,                                   -- 随访方式三值词表：PHONE 电话 / WECHAT 公众号 / REVISIT 复诊
    summary      VARCHAR(255) NULL,                                       -- 内容摘要（出院医嘱随访要素：复诊提示/用药指导/康复注意等）
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',                 -- PENDING 待执行 / DONE 已完成 / CANCELLED 已取消
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
COMMENT ON TABLE inpatient.follow_up_plan IS '随访计划（出院医嘱三要素之一）：离院确认时按出院后 N 日参数生成；触达经 M01 通知中心（缺位期降级工作站列表可见）';
CREATE INDEX idx_follow_up_plan_due ON inpatient.follow_up_plan (status, plan_date) WHERE deleted = 0;
CREATE TRIGGER trg_follow_up_plan_updated_at BEFORE UPDATE ON inpatient.follow_up_plan
    FOR EACH ROW EXECUTE FUNCTION public.fuyun_set_updated_at();
