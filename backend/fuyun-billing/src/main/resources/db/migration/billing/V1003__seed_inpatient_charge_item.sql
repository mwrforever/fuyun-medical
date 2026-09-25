-- V1003：住院计价项目种子（M13 住院计费联动，P2 PR-1 Task 13 条件迁移；通用段四位数，理由同 V1001）。
-- 先实测结论（brief 冻结 grep 面）：billing 迁移既有面（V600–V606）charge_item 零种子行——
--   V600 仅定义 item_class 词表注释含 BED/NURSING，无住院床位费/护理费项目行，本迁移补种。
-- 种子范围（brief 冻结最小集）：普通床位费（日切床位费计价入口，admitted 起费锚点与 02:30 日切
--   任务共用）+ 护理费。项目编码为 billing 侧院内物价码（InpatientChargeServiceImpl 常量同源），
--   消费端医嘱明细 itemCode 按码取价（PricingEngineServiceImpl.requireActiveByCode）。
-- 种子 ID 取小整数（V904 先例：跨表 ID 空间独立，雪花 ID 19 位量级永不冲突）。
-- 价格红线（A.4.2-8）：price BIGINT 存「分」；PUBLISHED 且 effective_to NULL 即当前唯一有效价
--   （uk_price_item_current 承载，snapshot 直接可取）。幂等形态：INSERT ... WHERE NOT EXISTS。
-- 演示价留痕：床位费 6000 分/护理费 3000 分为 P2 演示价，物价归集校准随运营配置修正。

-- 普通床位费（床日，60.00 元/床日）
INSERT INTO billing.charge_item (id, item_code, item_name, item_class, unit, exec_dept_id, price_flag, combo_flag, fee_category, status)
SELECT 610001, 'IN_BED_DAY', '普通床位费', 'BED', '日', NULL, 'SINGLE', FALSE, '床位费', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM billing.charge_item WHERE item_code = 'IN_BED_DAY' AND deleted = 0);

-- 护理费（床日，30.00 元/床日）
INSERT INTO billing.charge_item (id, item_code, item_name, item_class, unit, exec_dept_id, price_flag, combo_flag, fee_category, status)
SELECT 610002, 'IN_NURSING_DAY', '护理费', 'NURSING', '日', NULL, 'SINGLE', FALSE, '护理费', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM billing.charge_item WHERE item_code = 'IN_NURSING_DAY' AND deleted = 0);

-- 价格版本种子（版本 1，发布即生效且为当前唯一有效版本）
INSERT INTO billing.charge_item_price (id, charge_item_id, price, version, effective_from, effective_to, price_source, approval_no, status)
SELECT 620001, 610001, 6000, 1, now(), NULL, 'OFFICIAL_DOC', 'SEED-2026-P2', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM billing.charge_item_price WHERE charge_item_id = 610001 AND version = 1 AND deleted = 0);

INSERT INTO billing.charge_item_price (id, charge_item_id, price, version, effective_from, effective_to, price_source, approval_no, status)
SELECT 620002, 610002, 3000, 1, now(), NULL, 'OFFICIAL_DOC', 'SEED-2026-P2', 'PUBLISHED'
WHERE NOT EXISTS (SELECT 1 FROM billing.charge_item_price WHERE charge_item_id = 610002 AND version = 1 AND deleted = 0);
