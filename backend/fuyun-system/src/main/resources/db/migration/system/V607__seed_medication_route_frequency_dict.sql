-- V607：给药途径/用药频次字典预置（M01 字典域，M06 Spec :207「字典引用（给药途径/频次/诊断 code，
-- 不自建副本）」消费面；用户 2026-09-19 裁决 PR-4 顺手预置，推翻 2026-09-18「随 M01 字典管理交付」
-- 结论——TASK.md W-21 销项载体）。
-- 落位合法性（前置项 P-3/偏差④）：system 登记段=(300,399)+(500,None)，607∈通用段且 607>基线全局
--   最大 V606——check_out_of_order 放行（V304/V504 均小于基线被拦，禁试）；Flyway 执行序 V607 先于
--   pharmacy V700–V703（全部 locations 版本号升序应用），M06 消费无时序依赖。
-- 形态同构 V303 种子先例：INSERT ... WHERE NOT EXISTS 幂等（防人工重放重复插入）；种子行取小整数
--   ID（跨表 ID 空间独立，雪花 ID 为 19 位量级永不冲突；本迁移占用 dict_type 1–2、dict_version 1–2、
--   dict_item 1–25，后续种子迁移续用未占小整数）；审计列取 DEFAULT 'system'，updated_at 走列默认
--   now()（V301 触发器仅挂 UPDATE）。
-- 版本状态直接 PUBLISHED（effective_at/published_at=now()，M01 §5 P0「发布即生效」口径）；不发
--   system.dict.published——该事件由 M01 管理面发布动作（IDictVersionService.publish）运行期发出，
--   迁移种子无运行时发布动作（与 V303 RBAC 种子不发自缔事件行为一致）；消费方经
--   GET /api/v1/system/dicts/{type} 读接口拉取/重启加载（Task 10 仅存版本水位，条目级消费随 P3）。
-- type_code 与 Task 10 PharmacyMasterDataCache 白名单常量逐字同源（medication.route/
--   medication.frequency）；条目编码为行业惯例默认清单（拉丁/英文缩写，经待批 7 批复、可经 M01
--   字典管理面扩充调整）；字典条目无启用位（V301 结构：停用经版本 DEPRECATED/条目逻辑删承载），
--   sort 为排序位列（小者在前）。
-- 校验分工（P-3 择「最小冲突」案）：给药途径主校验维持 drug.route_codes 院内途径集（PH-1015 不变），
--   字典供前端下拉取值源与 M01 管理面维护；频次维持非空校验（条目级校验随 P3）。

-- ---------------------------------------------------------------- 1. 字典类型（两类）
INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 1, 'medication.route', '给药途径', false, 'M06 药事域给药途径字典（PR-4 预置，行业惯例默认清单可扩充）'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'medication.route' AND deleted = 0);

INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 2, 'medication.frequency', '用药频次', false, 'M06 药事域用药频次字典（PR-4 预置，行业惯例默认清单可扩充）'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'medication.frequency' AND deleted = 0);

-- ---------------------------------------------------------------- 2. 字典版本（各 v1，直接 PUBLISHED）
INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 1, 1, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 1 AND version = 1 AND deleted = 0);

INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 2, 2, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 2 AND version = 1 AND deleted = 0);

-- ---------------------------------------------------------------- 3. 给药途径条目（15 条，dict_version_id=1）
-- 口服（per os）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 1, 1, 'PO', '口服', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'PO' AND deleted = 0);

-- 静脉注射（intravenous injection）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 2, 1, 'IV', '静脉注射', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'IV' AND deleted = 0);

-- 静脉滴注（intravenous drip）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 3, 1, 'VD', '静脉滴注', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'VD' AND deleted = 0);

-- 肌肉注射（intramuscular injection）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 4, 1, 'IM', '肌肉注射', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'IM' AND deleted = 0);

-- 皮下注射（hypodermic injection）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 5, 1, 'H', '皮下注射', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'H' AND deleted = 0);

-- 皮内注射（intradermal injection）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 6, 1, 'ID', '皮内注射', 6
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'ID' AND deleted = 0);

-- 外用（external use）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 7, 1, 'OD', '外用', 7
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'OD' AND deleted = 0);

-- 吸入（inhalation）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 8, 1, 'INH', '吸入', 8
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'INH' AND deleted = 0);

-- 直肠给药（per rectum）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 9, 1, 'PR', '直肠给药', 9
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'PR' AND deleted = 0);

-- 舌下含服（sublingual）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 10, 1, 'SL', '舌下含服', 10
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'SL' AND deleted = 0);

-- 阴道给药（per vaginam）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 11, 1, 'PV', '阴道给药', 11
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'PV' AND deleted = 0);

-- 尿道灌注（per urethram，拉丁缩写惯例同 PR/PV）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 12, 1, 'UR', '尿道灌注', 12
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'UR' AND deleted = 0);

-- 局部注射（local injection）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 13, 1, 'LI', '局部注射', 13
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'LI' AND deleted = 0);

-- 关节腔注射（intra-articular injection，国际通用缩写）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 14, 1, 'IA', '关节腔注射', 14
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'IA' AND deleted = 0);

-- 硬膜外给药（epidural administration）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 15, 1, 'EPI', '硬膜外给药', 15
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 1 AND item_code = 'EPI' AND deleted = 0);

-- ---------------------------------------------------------------- 4. 用药频次条目（10 条，dict_version_id=2）
-- 每日一次（quaque die）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 16, 2, 'qd', '每日一次', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'qd' AND deleted = 0);

-- 每日两次（bis die）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 17, 2, 'bid', '每日两次', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'bid' AND deleted = 0);

-- 每日三次（ter die）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 18, 2, 'tid', '每日三次', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'tid' AND deleted = 0);

-- 每日四次（quater die）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 19, 2, 'qid', '每日四次', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'qid' AND deleted = 0);

-- 隔日一次（quaque altera die）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 20, 2, 'qod', '隔日一次', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'qod' AND deleted = 0);

-- 每 8 小时一次（quaque 8 hora）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 21, 2, 'q8h', '每 8 小时', 6
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'q8h' AND deleted = 0);

-- 每 12 小时一次（quaque 12 hora）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 22, 2, 'q12h', '每 12 小时', 7
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'q12h' AND deleted = 0);

-- 每晚一次（quaque nocte）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 23, 2, 'qn', '每晚一次', 8
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'qn' AND deleted = 0);

-- 必要时（pro re nata）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 24, 2, 'prn', '必要时', 9
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'prn' AND deleted = 0);

-- 立即（statim）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 25, 2, 'st', '立即', 10
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 2 AND item_code = 'st' AND deleted = 0);

-- 预置合计 25 条（给药途径 15 + 用药频次 10）；行数断言由 Task 13 Step 4 真栈探针承载。
