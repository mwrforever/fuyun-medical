-- V606：退费二级审批链（FU-M13-03；Spec §6「大额（参数阈值）、医保已结算、票据已开具 →
--   财务/医保办二级审批」）——CHANGELOG 2026-09-18「PR-3 评审修复轮」条目先记再改后落盘。
-- 变更一（列宽放宽）：新状态 PENDING_SECOND_APPROVAL（23 字符）超 V603 原 VARCHAR(16)；
--   PG 加长 VARCHAR 为元数据级变更（存量行完整保全、无表重写），V603 正文按迁移不可变原则禁改，
--   值域口径经下方 COMMENT ON 就地更新。
-- 变更二（审批链引用列）：一级审批人/时刻（first_approver/first_approved_at）——L2 单一级批后落痕，
--   first_approver 兼作「连批守卫」比对位（二级批人≠一级批人，BILL-1020 语义扩展）。
-- 列宽口径：first_approver VARCHAR(32)（操作者标识）；时刻列 TIMESTAMPTZ 与 V603 approved_at 同源。

ALTER TABLE billing.refund_request ALTER COLUMN status TYPE VARCHAR(32);

COMMENT ON COLUMN billing.refund_request.status IS
    'DRAFT/PENDING_APPROVAL/PENDING_SECOND_APPROVAL/APPROVED/EXECUTED/REJECTED（V606 放宽列宽至 32）';

ALTER TABLE billing.refund_request ADD COLUMN first_approver VARCHAR(32) NULL;

COMMENT ON COLUMN billing.refund_request.first_approver IS
    '一级审批人（L2 二级审批链留痕；连批守卫：二级批人≠本值，BILL-1020）';

ALTER TABLE billing.refund_request ADD COLUMN first_approved_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN billing.refund_request.first_approved_at IS
    '一级审批时刻（L2 二级审批链留痕）';
