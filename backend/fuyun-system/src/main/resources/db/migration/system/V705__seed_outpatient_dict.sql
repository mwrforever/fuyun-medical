-- V705：门诊三类字典预置（03 Spec §8「字典（号别/就诊类型/离院去向）引用 M01 字典 code，不自建副本」；
-- recon 未列，属计划补充（待批 2）——前端下拉与 visit_type/disposition 词表校验取值源）。
-- 落位合法性：system 通用段 V705>基线最大 V703（同 V704）；条目编码=国标/行业标准清单：
--   visit_type 对齐信息页就诊类型代码（调研依据 1：1 急诊/2 普通/3 特需/4 互联网诊疗/5 MDT/9 其他）；
--   disposition 对齐急诊患者去向八类代码（1 医嘱离院/2 医嘱转院/3 医嘱转社区/4 非医嘱离院/5 死亡/
--   6 急诊留观/7 急诊转住院/9 其他）；appt_type=号别五类（普通/专家/专病/急诊/复诊）。
-- PUBLISHED 直落、不发 system.dict.published（V607 同口径注记）。
-- 形态同构 V607：INSERT ... WHERE NOT EXISTS 幂等（dict_item 按 dict_version_id+item_code 判重）；
--   占用 dict_type 3–5、dict_version 3–5、dict_item 26–44（V607 已占 type 1–2/version 1–2/item 1–25，
--   小整数续用）；全部 25 条 INSERT 逐条全文落死（code/name/sort 已冻结的机械展开，无压缩面）。

-- ---------------------------------------------------------------- 1. 字典类型（三类）
INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 3, 'outpatient.visit-type', '就诊类型', true, 'M03 门（急）诊信息页就诊类型代码（国卫办医政发〔2024〕16 号）'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'outpatient.visit-type' AND deleted = 0);

INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 4, 'outpatient.disposition', '离院去向', true, 'M03 离院去向代码（急诊患者去向八类，含其他）'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'outpatient.disposition' AND deleted = 0);

INSERT INTO system.dict_type (id, type_code, type_name, national_standard, remark)
SELECT 5, 'outpatient.appt-type', '号别', false, '排班号别五类，可扩充'
WHERE NOT EXISTS (SELECT 1 FROM system.dict_type WHERE type_code = 'outpatient.appt-type' AND deleted = 0);

-- ---------------------------------------------------------------- 2. 字典版本（各 v1，直接 PUBLISHED）
INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 3, 3, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 3 AND version = 1 AND deleted = 0);

INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 4, 4, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 4 AND version = 1 AND deleted = 0);

INSERT INTO system.dict_version (id, dict_type_id, version, status, effective_at, published_at)
SELECT 5, 5, 1, 'PUBLISHED', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM system.dict_version WHERE dict_type_id = 5 AND version = 1 AND deleted = 0);

-- ---------------------------------------------------------------- 3. 就诊类型条目（六条，dict_version_id=3）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 26, 3, 'EMERGENCY', '急诊', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'EMERGENCY' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 27, 3, 'GENERAL', '普通门诊', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'GENERAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 28, 3, 'SPECIAL', '特需门诊', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'SPECIAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 29, 3, 'INTERNET', '互联网诊疗', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'INTERNET' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 30, 3, 'MDT', '多学科诊疗', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'MDT' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 31, 3, 'OTHER', '其他', 6
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 3 AND item_code = 'OTHER' AND deleted = 0);

-- ---------------------------------------------------------------- 4. 离院去向条目（八条，dict_version_id=4）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 32, 4, 'DISCHARGE_HOME', '医嘱离院', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'DISCHARGE_HOME' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 33, 4, 'TRANSFER_HOSPITAL', '医嘱转院', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'TRANSFER_HOSPITAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 34, 4, 'TRANSFER_COMMUNITY', '医嘱转社区', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'TRANSFER_COMMUNITY' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 35, 4, 'NON_MEDICAL_LEAVE', '非医嘱离院', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'NON_MEDICAL_LEAVE' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 36, 4, 'DEATH', '死亡', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'DEATH' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 37, 4, 'OBSERVATION', '急诊留观', 6
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'OBSERVATION' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 38, 4, 'TRANSFER_INPATIENT', '急诊转住院', 7
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'TRANSFER_INPATIENT' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 39, 4, 'OTHER', '其他', 8
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 4 AND item_code = 'OTHER' AND deleted = 0);

-- ---------------------------------------------------------------- 5. 号别条目（五条，dict_version_id=5）
INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 40, 5, 'GENERAL', '普通', 1
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'GENERAL' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 41, 5, 'EXPERT', '专家', 2
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'EXPERT' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 42, 5, 'SPECIAL_DISEASE', '专病', 3
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'SPECIAL_DISEASE' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 43, 5, 'EMERGENCY', '急诊', 4
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'EMERGENCY' AND deleted = 0);

INSERT INTO system.dict_item (id, dict_version_id, item_code, item_name, sort)
SELECT 44, 5, 'REVISIT', '复诊', 5
WHERE NOT EXISTS (SELECT 1 FROM system.dict_item WHERE dict_version_id = 5 AND item_code = 'REVISIT' AND deleted = 0);

-- 预置合计 19 条（就诊类型 6 + 离院去向 8 + 号别 5）+ 类型 3 + 版本 3 = 25 条 INSERT；
-- 行数断言由 Task 13 Step 4 真栈探针承载。
