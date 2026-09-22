-- V706：prescription_item.status 列注释订正（W-23，W-22⑨ DB 残余面收口）。
-- 事实口径（W-22⑨ 核实结论；代码侧 javadoc 已随 W-22 fix PR 订正，本次仅订正 DB 内联注释）：
--   主代码对 pharmacy.prescription_item.status 零写入点——全量检索 backend/fuyun-pharmacy 写路径
--   （updateById / setStatus / 注解 SQL / XML mapper）后，唯一写 pharmacy.prescription_item 的语句为
--   PrescriptionItemMapper.accumulateReturnedQuantity（仅 returned_quantity 原子累加）；发药中明细
--   退场写的是 pharmacy.dispense_item.item_status（V703 :95，DispenseServiceImpl 退场分支），
--   退药链只回写 returned_quantity。故 V701 :66 内联注释「returned_quantity/status 由退药链回写」
--   中 status 半句失实（「退药回写」仅对 returned_quantity 成立）。
-- 承载方式：宪法 A.4.1-3 禁改已应用迁移（V701 checksum 校验必失败），错误修正只能追加新版本迁移，
--   故以本迁移的 COMMENT ON COLUMN 就地更新权威口径，不改 V701 正文（先例：V606 同款 COMMENT ON）。
-- 号段：pharmacy V706——V704/V705 已被 PR-5 占用（全局最大已应用版本=V705），V706 > V705 满足
--   乱序守卫；登记载体 docs/migrations/flyway-version-registry.md 与 CHANGELOG.md 同步更新。
-- 落款：W-23（2026-09-22）。

COMMENT ON COLUMN pharmacy.prescription_item.status IS '明细状态 NORMAL/CANCELLED（发药中明细退场语义）；V706 订正（W-23）：主代码对该列零写入点——发药中明细退场写 pharmacy.dispense_item.item_status（V703），退药链仅经 PrescriptionItemMapper.accumulateReturnedQuantity 回写 returned_quantity，均不触本列，本列恒为插入期默认值 NORMAL；V701 内联注释「returned_quantity/status 由退药链回写」中 status 半句失实，以本注释为权威口径';
