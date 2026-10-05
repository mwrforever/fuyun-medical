-- V1116：全量权限点种子（P2 PR-4D Task 2 / 计划 2026-10-03-p2-pr4d-rbac-full 设计裁定 D1/D2/D6）——
--   sys_permission 双命名空间全量登记 + V303 旧 6 点 perm_code 方法前缀 UPDATE。
-- 登记计数：API 权限点 302 + MENU 权限点 32 + V303 旧 6 点 UPDATE 6（附件 A/B 权威矩阵，矩阵即权威源）。
--   口径差异登记：计划/brief 头注「MENU 33」为计数误差——workstation 路由 33 条 permission meta 中
--   patient:archive:search 重复登记两次，去重后 32 码，与附件 B 物理 32 行、前端冒号码全集逐一致。
-- 幂等形态：INSERT ... SELECT ... WHERE NOT EXISTS ... AND deleted = 0（V303 先例，与 perm_code 部分唯一
--   索引 uk_sys_permission_perm_code 同口径，重放零重复零唯一冲突）；UPDATE 用 perm_code + perm_type 双条件
--   守卫（重放幂等：旧值已改后 0 行命中，照 V1111 守卫先例），id 列不动（V303 ADMIN 绑定关系按 id 不受影响）。
-- perm_code 形态（D1/D2 裁定）：API 型 = 动词+空格+路径模板（如 POST /api/v1/billing/refunds/{id}/approve），
--   解决同路径 GET/POST 权限差异；MENU 型 = 冒号码（workstation 路由 meta.permission 既有形态，前端守卫消费）。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认 'system'
--   （backend 宪法 A.4.2-9）；updated_at 另由 V300 触发器统一维护。
-- ID 取值：API 点 1116000000000000001+序、MENU 点 1116001000000000001+序（19 位内固定值，前缀分段防撞）；
--   附件 A 中与 UPDATE 承接的 6 点其 INSERT 守卫天然 no-op（该码已由改名后的 V303 旧行占用，id 1~6 保留），
--   新 id 不被消耗，Task 3 的 V1117 绑定一律按 perm_code join 解析 id，禁手写。

-- ---------------------------------------------------------------- 1. V303 旧 6 点 perm_code 方法前缀 UPDATE（D1 裁定）
-- 旧值全串按 V303 原文匹配（权限点 1 的模板变量是 {type} 而非矩阵的 {typeCode}）；perm_name 与 V303 一致不动。
UPDATE system.sys_permission
SET perm_code = 'GET /api/v1/system/dicts/{typeCode}'
WHERE perm_code = '/api/v1/system/dicts/{type}' AND perm_type = 'API';

UPDATE system.sys_permission
SET perm_code = 'POST /api/v1/system/dict-types'
WHERE perm_code = '/api/v1/system/dict-types' AND perm_type = 'API';

UPDATE system.sys_permission
SET perm_code = 'POST /api/v1/system/dict-types/{typeCode}/versions'
WHERE perm_code = '/api/v1/system/dict-types/{typeCode}/versions' AND perm_type = 'API';

UPDATE system.sys_permission
SET perm_code = 'POST /api/v1/system/dict-versions/{versionId}/items'
WHERE perm_code = '/api/v1/system/dict-versions/{versionId}/items' AND perm_type = 'API';

UPDATE system.sys_permission
SET perm_code = 'POST /api/v1/system/dict-versions/{versionId}/publish'
WHERE perm_code = '/api/v1/system/dict-versions/{versionId}/publish' AND perm_type = 'API';

UPDATE system.sys_permission
SET perm_code = 'POST /api/v1/system/practice/check'
WHERE perm_code = '/api/v1/system/practice/check' AND perm_type = 'API';

-- ---------------------------------------------------------------- 2. API 权限点 302 点（附件 A 逐行，perm_type='API'）
-- 后端 403 拦截器消费面；ADMIN 运行期全放不种绑定（D3），业务角色绑定归 V1117 按附件 A「授予角色」列。

-- ---- A.1 system 域（9 点；豁免 login/refresh/bigscreen-token/logout 四免认证面）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000001, 'GET /api/v1/system/dicts/{typeCode}', '字典发布版本读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/system/dicts/{typeCode}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000002, 'POST /api/v1/system/dict-types', '字典类型创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/dict-types' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000003, 'POST /api/v1/system/dict-types/{typeCode}/versions', '字典版本创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/dict-types/{typeCode}/versions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000004, 'POST /api/v1/system/dict-versions/{versionId}/items', '字典条目新增', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/dict-versions/{versionId}/items' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000005, 'POST /api/v1/system/dict-versions/{versionId}/publish', '字典版本发布', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/dict-versions/{versionId}/publish' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000006, 'POST /api/v1/system/practice/check', '执业授权校验', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/practice/check' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000007, 'POST /api/v1/system/practice/grants', '执业授权授予', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/practice/grants' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000008, 'POST /api/v1/system/practice/grants/{id}/withdraw', '执业授权撤回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/system/practice/grants/{id}/withdraw' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000009, 'GET /api/v1/system/practice/grants', '执业授权查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/system/practice/grants' AND deleted = 0);

-- ---- A.2 patient 域（34 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000010, 'GET /api/v1/patient/card-accounts/{id}', '卡账户读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/card-accounts/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000011, 'POST /api/v1/patient/card-accounts/{id}/freeze', '卡账户冻结', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/card-accounts/{id}/freeze' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000012, 'POST /api/v1/patient/card-accounts/{id}/close', '卡账户销户', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/card-accounts/{id}/close' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000013, 'GET /api/v1/patient/card-accounts/{id}/txns', '卡账户流水查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/card-accounts/{id}/txns' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000014, 'POST /api/v1/patient/cards/issue', '就诊卡发放', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/cards/issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000015, 'POST /api/v1/patient/cards/bind', '就诊卡绑定', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/cards/bind' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000016, 'POST /api/v1/patient/cards/loss/{cardNo}', '就诊卡挂失', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/cards/loss/{cardNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000017, 'POST /api/v1/patient/cards/replace', '就诊卡补卡', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/cards/replace' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000018, 'POST /api/v1/patient/cards/unbind/{cardNo}', '就诊卡解绑', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/cards/unbind/{cardNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000019, 'GET /api/v1/patient/cards/{cardNo}', '就诊卡读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/cards/{cardNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000020, 'GET /api/v1/patient/possible-duplicates', '疑似重复工作台', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/possible-duplicates' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000021, 'POST /api/v1/patient/possible-duplicates/{id}/exclude', '疑似重复排除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/possible-duplicates/{id}/exclude' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000022, 'POST /api/v1/patient/merges', 'EMPI 合并申请', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/merges' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000023, 'POST /api/v1/patient/merges/{id}/approve', 'EMPI 合并审批', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/merges/{id}/approve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000024, 'POST /api/v1/patient/merges/{id}/split', 'EMPI 拆分', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/merges/{id}/split' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000025, 'GET /api/v1/patient/patients/{patientId}/health-summary', '健康档案摘要', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/patients/{patientId}/health-summary' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000026, 'POST /api/v1/patient/patients/{patientId}/health-items', '健康档案项新增', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/patients/{patientId}/health-items' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000027, 'POST /api/v1/patient/health-items/{id}/correct', '健康档案项更正', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/health-items/{id}/correct' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000028, 'POST /api/v1/patient/patients', '患者建档', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/patients' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000029, 'POST /api/v1/patient/patients/match-check', '建档匹配检查', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/patients/match-check' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000030, 'GET /api/v1/patient/patients/{patientId}', '患者主档读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/patients/{patientId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000031, 'PUT /api/v1/patient/patients/{patientId}', '患者主档更新', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/patient/patients/{patientId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000032, 'GET /api/v1/patient/patients/search', '患者检索', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/patients/search' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000033, 'POST /api/v1/patient/patients/{patientId}/freeze', '患者冻结', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/patients/{patientId}/freeze' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000034, 'POST /api/v1/patient/patients/{patientId}/unfreeze', '患者解冻', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/patients/{patientId}/unfreeze' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000035, 'POST /api/v1/patient/identifiers/resolve', '患者标识解析', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/identifiers/resolve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000036, 'POST /api/v1/patient/patients/{patientId}/identifiers', '患者标识登记', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/patients/{patientId}/identifiers' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000037, 'GET /api/v1/patient/patients/{patientId}/identifiers', '患者标识查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/patients/{patientId}/identifiers' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000038, 'GET /api/v1/patient/privacy-auths', '隐私授权查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/privacy-auths' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000039, 'POST /api/v1/patient/privacy-auths', '隐私授权登记', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/privacy-auths' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000040, 'GET /api/v1/patient/privacy-mask-rules', '脱敏规则查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/privacy-mask-rules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000041, 'PUT /api/v1/patient/privacy-mask-rules/{ruleCode}', '脱敏规则维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/patient/privacy-mask-rules/{ruleCode}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000042, 'POST /api/v1/patient/privacy/unmask', '明文查阅', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/patient/privacy/unmask' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000043, 'GET /api/v1/patient/privacy-access-logs', '明文查阅审计', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/patient/privacy-access-logs' AND deleted = 0);

-- ---- A.3 billing 域（34 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000044, 'POST /api/v1/billing/arrears-approvals', '欠费审批申请', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/arrears-approvals' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000045, 'POST /api/v1/billing/arrears-approvals/{approvalNo}/approve', '欠费审批通过', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/arrears-approvals/{approvalNo}/approve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000046, 'POST /api/v1/billing/arrears-approvals/{approvalNo}/reject', '欠费审批驳回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/arrears-approvals/{approvalNo}/reject' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000047, 'POST /api/v1/billing/charge-items', '收费项创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/charge-items' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000048, 'GET /api/v1/billing/charge-items/by-code/{itemCode}', '收费项编码查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/charge-items/by-code/{itemCode}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000049, 'POST /api/v1/billing/charge-items/{id}/combo-components', '组合收费项组件维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/charge-items/{id}/combo-components' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000050, 'GET /api/v1/billing/daily-lists', '日结单查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/daily-lists' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000051, 'POST /api/v1/billing/deposits', '押金收取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/deposits' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000052, 'GET /api/v1/billing/deposits', '押金查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/deposits' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000053, 'POST /api/v1/billing/pricing/quote', '计费试算', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/pricing/quote' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000054, 'POST /api/v1/billing/fees/manual', '手工计费', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/fees/manual' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000055, 'GET /api/v1/billing/fees', '费用查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/fees' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000056, 'POST /api/v1/billing/fees/{id}/cancel', '费用作废', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/fees/{id}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000057, 'POST /api/v1/billing/insurance/register', '医保登记', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/insurance/register' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000058, 'POST /api/v1/billing/insurance/fee-uploads', '医保费用上传', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/insurance/fee-uploads' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000059, 'POST /api/v1/billing/insurance/reverse', '医保冲正', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/insurance/reverse' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000060, 'GET /api/v1/billing/insurance/call-logs', '医保调用日志', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/insurance/call-logs' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000061, 'POST /api/v1/billing/insurance/call-logs/{id}/compensation', '医保补偿重试', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/insurance/call-logs/{id}/compensation' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000062, 'POST /api/v1/billing/insurance/credential', '医保凭证配置', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/insurance/credential' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000063, 'POST /api/v1/billing/insurance-mappings/upsert', '医保对照维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/insurance-mappings/upsert' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000064, 'GET /api/v1/billing/insurance-mappings/{chargeItemId}', '医保对照查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/insurance-mappings/{chargeItemId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000065, 'POST /api/v1/billing/charge-items/{id}/prices', '调价新草稿', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/charge-items/{id}/prices' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000066, 'POST /api/v1/billing/price-adjustments/{id}/publish', '调价发布', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/price-adjustments/{id}/publish' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000067, 'GET /api/v1/billing/charge-items/{id}/prices', '调价历史查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/charge-items/{id}/prices' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000068, 'POST /api/v1/billing/pricing-rules/upsert', '定价规则维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/pricing-rules/upsert' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000069, 'GET /api/v1/billing/pricing-rules', '定价规则查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/pricing-rules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000070, 'POST /api/v1/billing/refunds', '退费发起', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/refunds' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000071, 'POST /api/v1/billing/refunds/{id}/approve', '退费审批', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/refunds/{id}/approve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000072, 'POST /api/v1/billing/refunds/{id}/reject', '退费驳回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/refunds/{id}/reject' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000073, 'POST /api/v1/billing/refunds/{id}/execute', '退费执行', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/refunds/{id}/execute' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000074, 'GET /api/v1/billing/refunds', '退费单查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/refunds' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000075, 'POST /api/v1/billing/settlements/preview', '结算预览', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/settlements/preview' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000076, 'POST /api/v1/billing/settlements', '结算收费', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/billing/settlements' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000077, 'GET /api/v1/billing/settlements/{no}', '结算单查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/billing/settlements/{no}' AND deleted = 0);

-- ---- A.4 pharmacy 域（26 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000078, 'POST /api/v1/pharmacy/dispenses/{no}/pick', '门诊摆药拣药', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispenses/{no}/pick' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000079, 'POST /api/v1/pharmacy/dispenses/{no}/verify', '门诊摆药核对', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispenses/{no}/verify' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000080, 'POST /api/v1/pharmacy/dispenses/{no}/issue', '门诊摆药发药', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispenses/{no}/issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000081, 'POST /api/v1/pharmacy/dispense-returns', '门诊退药', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-returns' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000082, 'GET /api/v1/pharmacy/medication-occupancy', '给药占用查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/medication-occupancy' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000083, 'GET /api/v1/pharmacy/dispenses', '摆药单查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/dispenses' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000084, 'POST /api/v1/pharmacy/dispense-plans/generate', '住院摆药计划生成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-plans/generate' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000085, 'POST /api/v1/pharmacy/dispense-plans/{no}/pick', '住院摆药拣药', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-plans/{no}/pick' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000086, 'POST /api/v1/pharmacy/dispense-plans/{no}/verify', '住院摆药核对', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-plans/{no}/verify' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000087, 'POST /api/v1/pharmacy/dispense-plans/{no}/issue', '住院摆药发药', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-plans/{no}/issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000088, 'POST /api/v1/pharmacy/dispense-plans/{no}/deliver', '住院摆药配送', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-plans/{no}/deliver' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000089, 'POST /api/v1/pharmacy/dispense-plans/{no}/receive', '住院摆药签收', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/dispense-plans/{no}/receive' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000090, 'GET /api/v1/pharmacy/dispense-plans', '住院摆药计划查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/dispense-plans' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000091, 'GET /api/v1/pharmacy/dispense-plans/{no}/label', '摆药标签查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/dispense-plans/{no}/label' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000092, 'GET /api/v1/pharmacy/dispense-plans/{no}/returnable', '可退明细读面', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/dispense-plans/{no}/returnable' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000093, 'POST /api/v1/pharmacy/drugs', '药品创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/drugs' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000094, 'PUT /api/v1/pharmacy/drugs/{id}', '药品维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/pharmacy/drugs/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000095, 'GET /api/v1/pharmacy/drugs/{id}', '药品读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/drugs/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000096, 'POST /api/v1/pharmacy/drugs/{id}/insurance-mapping', '药品医保对照', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/drugs/{id}/insurance-mapping' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000097, 'GET /api/v1/pharmacy/drugs/search', '药品检索', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/drugs/search' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000098, 'POST /api/v1/pharmacy/prescriptions', '处方开立', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/prescriptions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000099, 'POST /api/v1/pharmacy/prescriptions/{no}/cancel', '处方作废', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/prescriptions/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000100, 'GET /api/v1/pharmacy/prescriptions', '处方查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/prescriptions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000101, 'GET /api/v1/pharmacy/review-tasks', '审方任务查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/pharmacy/review-tasks' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000102, 'POST /api/v1/pharmacy/review-tasks/{id}/approve', '审方通过', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/review-tasks/{id}/approve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000103, 'POST /api/v1/pharmacy/review-tasks/{id}/reject', '审方驳回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/pharmacy/review-tasks/{id}/reject' AND deleted = 0);

-- ---- A.5 outpatient 域（27 点；豁免 portal 匿名通道×3 与候诊榜快照×1）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000104, 'POST /api/v1/outpatient/appointments', '预约挂号', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/appointments' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000105, 'POST /api/v1/outpatient/appointments/{no}/take', '取号', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/appointments/{no}/take' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000106, 'POST /api/v1/outpatient/appointments/{no}/cancel', '退号', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/appointments/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000107, 'POST /api/v1/outpatient/appointments/{no}/reschedule', '改约', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/appointments/{no}/reschedule' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000108, 'GET /api/v1/outpatient/appt-credits', '号源信用查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/outpatient/appt-credits' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000109, 'POST /api/v1/outpatient/appt-credits/{id}/release', '号源信用解除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/appt-credits/{id}/release' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000110, 'POST /api/v1/outpatient/visits/{visitId}/prescriptions', '门诊开方', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/visits/{visitId}/prescriptions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000111, 'POST /api/v1/outpatient/orders/{no}/cancel', '门诊订单作废', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/orders/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000112, 'GET /api/v1/outpatient/orders', '门诊订单查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/outpatient/orders' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000113, 'POST /api/v1/outpatient/queue/call', '队列叫号', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/queue/call' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000114, 'POST /api/v1/outpatient/queue/tickets/{id}/pass', '候诊过号', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/queue/tickets/{id}/pass' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000115, 'POST /api/v1/outpatient/queue/tickets/{id}/recall', '候诊召回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/queue/tickets/{id}/recall' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000116, 'GET /api/v1/outpatient/schedule-templates', '排班模板查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/outpatient/schedule-templates' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000117, 'POST /api/v1/outpatient/schedule-templates', '排班模板创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/schedule-templates' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000118, 'PUT /api/v1/outpatient/schedule-templates', '排班模板更新', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/outpatient/schedule-templates' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000119, 'POST /api/v1/outpatient/schedules/generate', '号源批量生成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/schedules/generate' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000120, 'GET /api/v1/outpatient/schedules', '号源查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/outpatient/schedules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000121, 'POST /api/v1/outpatient/schedules/{id}/stop', '号源停用', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/schedules/{id}/stop' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000122, 'POST /api/v1/outpatient/schedules/{id}/resume', '号源恢复', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/schedules/{id}/resume' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000123, 'GET /api/v1/outpatient/number-pools/available', '号池可用查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/outpatient/number-pools/available' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000124, 'POST /api/v1/outpatient/number-pools/{id}/extra-quota', '号池加号', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/number-pools/{id}/extra-quota' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000125, 'POST /api/v1/outpatient/triage/check-in', '分诊报到', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/triage/check-in' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000126, 'POST /api/v1/outpatient/triage/adjust', '分诊调整', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/triage/adjust' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000127, 'POST /api/v1/outpatient/visits/{visitId}/admit', '接诊开始', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/visits/{visitId}/admit' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000128, 'POST /api/v1/outpatient/visits/{visitId}/finish', '接诊结束', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/visits/{visitId}/finish' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000129, 'GET /api/v1/outpatient/doctor/patient-queue', '医生候诊队列', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/outpatient/doctor/patient-queue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000130, 'POST /api/v1/outpatient/visits/{visitId}/orders', '门诊开单', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/outpatient/visits/{visitId}/orders' AND deleted = 0);

-- ---- A.6 inpatient 域（40 点）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000131, 'POST /api/v1/inpatient/admissions', '住院证开立', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/admissions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000132, 'GET /api/v1/inpatient/admissions', '住院登记查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/admissions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000133, 'POST /api/v1/inpatient/admissions/{no}/schedule', '住院证排床', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/admissions/{no}/schedule' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000134, 'POST /api/v1/inpatient/admissions/{no}/cancel', '住院证作废', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/admissions/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000135, 'POST /api/v1/inpatient/admissions/{no}/register', '入院登记', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/admissions/{no}/register' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000136, 'POST /api/v1/inpatient/visits/{visitId}/admit-ward', '入科接收', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/visits/{visitId}/admit-ward' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000137, 'GET /api/v1/inpatient/visits/arrears', '在院欠费查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/visits/arrears' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000138, 'GET /api/v1/inpatient/beds/map', '床位图查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/beds/map' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000139, 'POST /api/v1/inpatient/beds/{id}/reserve', '床位预留', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/beds/{id}/reserve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000140, 'POST /api/v1/inpatient/beds/{id}/assign', '床位分配', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/beds/{id}/assign' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000141, 'POST /api/v1/inpatient/beds/{id}/release', '床位释放', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/beds/{id}/release' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000142, 'POST /api/v1/inpatient/beds/{id}/disinfect-done', '床位消毒完成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/beds/{id}/disinfect-done' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000143, 'POST /api/v1/inpatient/beds/{id}/maintain', '床位维修上报', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/beds/{id}/maintain' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000144, 'POST /api/v1/inpatient/beds/{id}/maintain-done', '床位维修完成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/beds/{id}/maintain-done' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000145, 'POST /api/v1/inpatient/consultations', '会诊申请', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/consultations' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000146, 'POST /api/v1/inpatient/consultations/{no}/accept', '会诊接受', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/consultations/{no}/accept' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000147, 'POST /api/v1/inpatient/consultations/{no}/opinion', '会诊意见', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/consultations/{no}/opinion' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000148, 'POST /api/v1/inpatient/consultations/{no}/cancel', '会诊取消', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/consultations/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000149, 'GET /api/v1/inpatient/consultations', '会诊查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/consultations' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000150, 'POST /api/v1/inpatient/visits/{visitId}/discharge-request', '出院申请', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/visits/{visitId}/discharge-request' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000151, 'POST /api/v1/inpatient/discharge-requests/{no}/cancel', '出院申请取消', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/discharge-requests/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000152, 'GET /api/v1/inpatient/discharge-requests/{no}/clearance', '出院结算清单', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/discharge-requests/{no}/clearance' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000153, 'POST /api/v1/inpatient/discharge-requests/{no}/confirm', '出院确认', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/discharge-requests/{no}/confirm' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000154, 'POST /api/v1/inpatient/visits/{visitId}/orders', '住院医嘱开立', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/visits/{visitId}/orders' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000155, 'GET /api/v1/inpatient/orders', '住院医嘱查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/orders' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000156, 'GET /api/v1/inpatient/orders/{no}', '住院医嘱详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/orders/{no}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000157, 'POST /api/v1/inpatient/orders/{no}/stop', '医嘱停止', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/{no}/stop' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000158, 'POST /api/v1/inpatient/orders/{no}/cancel', '医嘱作废', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000159, 'POST /api/v1/inpatient/orders/{no}/revoke-audit', '医嘱撤销审核', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/{no}/revoke-audit' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000160, 'POST /api/v1/inpatient/orders/reorganize', '医嘱重整', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/reorganize' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000161, 'POST /api/v1/inpatient/orders/{no}/resubmit', '医嘱重提交', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/{no}/resubmit' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000162, 'POST /api/v1/inpatient/orders/{no}/oral-confirm', '口服药确认', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/{no}/oral-confirm' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000163, 'GET /api/v1/inpatient/order-plans', '执行计划查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/order-plans' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000164, 'POST /api/v1/inpatient/order-plans/standby-trigger', '备用医嘱触发', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/order-plans/standby-trigger' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000165, 'POST /api/v1/inpatient/order-plans/{no}/execute-confirm', '执行计划确认', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/order-plans/{no}/execute-confirm' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000166, 'GET /api/v1/inpatient/orders/{no}/trace', '医嘱轨迹查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/orders/{no}/trace' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000167, 'GET /api/v1/inpatient/transfer-worklist', '转科工作台', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/inpatient/transfer-worklist' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000168, 'POST /api/v1/inpatient/orders/transfer-check', '转科医嘱核对', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/orders/transfer-check' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000169, 'POST /api/v1/inpatient/visits/{visitId}/transfer', '转科执行', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/visits/{visitId}/transfer' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000170, 'POST /api/v1/inpatient/visits/{visitId}/change-bed', '转床执行', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/inpatient/visits/{visitId}/change-bed' AND deleted = 0);

-- ---- A.7 nursing 域（52 点；豁免哨兵 board/{wardId}）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000171, 'POST /api/v1/nursing/adverse-events', '不良事件上报', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/adverse-events' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000172, 'GET /api/v1/nursing/adverse-events', '不良事件查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/adverse-events' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000173, 'POST /api/v1/nursing/adverse-events/{no}/handle', '不良事件处置', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/adverse-events/{no}/handle' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000174, 'POST /api/v1/nursing/adverse-events/{no}/close', '不良事件关闭', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/adverse-events/{no}/close' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000175, 'POST /api/v1/nursing/adverse-events/{no}/return', '不良事件退回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/adverse-events/{no}/return' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000176, 'GET /api/v1/nursing/stats/adverse-events', '不良事件统计', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/stats/adverse-events' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000177, 'POST /api/v1/nursing/executions/{no}/needle-out', '输液拔针', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/executions/{no}/needle-out' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000178, 'GET /api/v1/nursing/infusions/active', '在输列表查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/infusions/active' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000179, 'POST /api/v1/nursing/io-records', '出入量记录', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/io-records' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000180, 'GET /api/v1/nursing/io-records', '出入量查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/io-records' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000181, 'POST /api/v1/nursing/io-summaries', '出入量小结', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/io-summaries' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000182, 'GET /api/v1/nursing/io-summaries', '出入量小结查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/io-summaries' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000183, 'GET /api/v1/nursing/assessment-scales', '评估量表查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/assessment-scales' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000184, 'POST /api/v1/nursing/assessments', '护理评估', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/assessments' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000185, 'GET /api/v1/nursing/assessments', '评估记录查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/assessments' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000186, 'POST /api/v1/nursing/nursing-records', '护理文书书写', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/nursing-records' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000187, 'GET /api/v1/nursing/nursing-records', '护理文书查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/nursing-records' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000188, 'GET /api/v1/nursing/nursing-records/{recordNo}', '护理文书详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/nursing-records/{recordNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000189, 'POST /api/v1/nursing/nursing-records/{recordNo}/submit', '护理文书提交', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/nursing-records/{recordNo}/submit' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000190, 'POST /api/v1/nursing/nursing-records/{recordNo}/revise', '护理文书修订', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/nursing-records/{recordNo}/revise' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000191, 'POST /api/v1/nursing/tasks', '护理任务创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/tasks' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000192, 'GET /api/v1/nursing/tasks', '护理任务查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/tasks' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000193, 'POST /api/v1/nursing/tasks/{taskNo}/complete', '护理任务完成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/tasks/{taskNo}/complete' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000194, 'POST /api/v1/nursing/tasks/{taskNo}/claim', '护理任务认领', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/tasks/{taskNo}/claim' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000195, 'POST /api/v1/nursing/tasks/generate-routine', '常规任务生成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/tasks/generate-routine' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000196, 'POST /api/v1/nursing/tasks/{taskNo}/cancel', '护理任务取消', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/tasks/{taskNo}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000197, 'GET /api/v1/nursing/executions', '执行单查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/executions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000198, 'POST /api/v1/nursing/executions/{no}/sign-receive', '执行单签收', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/executions/{no}/sign-receive' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000199, 'POST /api/v1/nursing/executions/{no}/check', '执行单核对', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/executions/{no}/check' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000200, 'POST /api/v1/nursing/executions/{no}/start', '执行开始', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/executions/{no}/start' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000201, 'POST /api/v1/nursing/executions/{no}/finish', '执行完成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/executions/{no}/finish' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000202, 'POST /api/v1/nursing/executions/{no}/cancel', '执行取消', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/executions/{no}/cancel' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000203, 'GET /api/v1/nursing/executions/occupancy', '给药占用查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/executions/occupancy' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000204, 'GET /api/v1/nursing/executions/{no}/trace', '执行轨迹查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/executions/{no}/trace' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000205, 'POST /api/v1/nursing/pda/override-check', '破码双授权', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/pda/override-check' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000206, 'GET /api/v1/nursing/pda/patient-summary', 'PDA 患者摘要', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/pda/patient-summary' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000207, 'POST /api/v1/nursing/pda/patrol', 'PDA 巡视打卡', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/pda/patrol' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000208, 'POST /api/v1/nursing/handovers/generate', '交班报告生成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/handovers/generate' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000209, 'POST /api/v1/nursing/handovers/{handoverNo}/complete', '交班完成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/handovers/{handoverNo}/complete' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000210, 'GET /api/v1/nursing/handovers', '交班查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/handovers' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000211, 'GET /api/v1/nursing/temperature-charts', '体温图查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/temperature-charts' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000212, 'POST /api/v1/nursing/temperature-charts/{visitId}/special-events', '体温图特殊事件', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/temperature-charts/{visitId}/special-events' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000213, 'POST /api/v1/nursing/vital-signs', '体征录入', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/vital-signs' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000214, 'GET /api/v1/nursing/vital-signs', '体征查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/vital-signs' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000215, 'GET /api/v1/nursing/vital-signs/pending-review', '体征待复审查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/vital-signs/pending-review' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000216, 'POST /api/v1/nursing/vital-signs/{id}/confirm', '体征复审确认', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/vital-signs/{id}/confirm' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000217, 'POST /api/v1/nursing/vital-signs/{id}/reject', '体征复审驳回', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/vital-signs/{id}/reject' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000218, 'GET /api/v1/nursing/ward-patients', '病区患者列表', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/ward-patients' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000219, 'GET /api/v1/nursing/ward-patients/{visitId}', '病区患者详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/ward-patients/{visitId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000220, 'GET /api/v1/nursing/assignments', '责任分配查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/nursing/assignments' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000221, 'POST /api/v1/nursing/assignments', '责任分配维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/nursing/assignments' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000222, 'DELETE /api/v1/nursing/assignments/{id}', '责任分配解除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'DELETE /api/v1/nursing/assignments/{id}' AND deleted = 0);

-- ---- A.8 iot 域（52 点；豁免哨兵 alarms GET 与非 /api/v1 的 ingest 通道）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000223, 'POST /api/v1/iot/alarms/{alarmNo}/acknowledge', '告警确认', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/alarms/{alarmNo}/acknowledge' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000224, 'POST /api/v1/iot/alarms/{alarmNo}/close', '告警关闭', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/alarms/{alarmNo}/close' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000225, 'GET /api/v1/iot/alarm-rules', '告警规则查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/alarm-rules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000226, 'POST /api/v1/iot/alarm-rules', '告警规则创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/alarm-rules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000227, 'PUT /api/v1/iot/alarm-rules/{id}', '告警规则更新', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/iot/alarm-rules/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000228, 'DELETE /api/v1/iot/alarm-rules/{id}', '告警规则删除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'DELETE /api/v1/iot/alarm-rules/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000229, 'POST /api/v1/iot/alarm-rules/{id}/simulate', '告警规则模拟', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/alarm-rules/{id}/simulate' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000230, 'POST /api/v1/iot/bindings', '设备绑定创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/bindings' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000231, 'POST /api/v1/iot/bindings/{deviceId}/unbind', '设备解绑', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/bindings/{deviceId}/unbind' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000232, 'GET /api/v1/iot/bindings', '设备绑定查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/bindings' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000233, 'GET /api/v1/iot/bindings/wards/{wardId}', '病区绑定清单', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/bindings/wards/{wardId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000234, 'GET /api/v1/iot/bindings/devices/{deviceId}/active', '设备当前绑定查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/bindings/devices/{deviceId}/active' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000235, 'POST /api/v1/iot/commands/confirm-challenge', '命令挑战确认', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/commands/confirm-challenge' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000236, 'POST /api/v1/iot/commands', '设备命令下发', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/commands' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000237, 'GET /api/v1/iot/commands', '命令查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/commands' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000238, 'GET /api/v1/iot/commands/{commandNo}', '命令详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/commands/{commandNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000239, 'GET /api/v1/iot/consume-errors', '消费错误查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/consume-errors' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000240, 'POST /api/v1/iot/consume-errors/{errorId}/replay', '消费错误重放', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/consume-errors/{errorId}/replay' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000241, 'POST /api/v1/iot/consume-errors/{errorId}/abandon', '消费错误放弃', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/consume-errors/{errorId}/abandon' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000242, 'GET /api/v1/iot/dashboard/summary', '看板汇总', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/dashboard/summary' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000243, 'GET /api/v1/iot/dashboard/wards/{wardId}', '病区看板', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/dashboard/wards/{wardId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000244, 'POST /api/v1/iot/devices', '设备注册', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/devices' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000245, 'GET /api/v1/iot/devices', '设备查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/devices' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000246, 'GET /api/v1/iot/devices/{deviceId}', '设备详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/devices/{deviceId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000247, 'POST /api/v1/iot/devices/{deviceId}/credential-reset', '设备凭证重置', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/devices/{deviceId}/credential-reset' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000248, 'POST /api/v1/iot/devices/{deviceId}/disable', '设备停用', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/devices/{deviceId}/disable' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000249, 'GET /api/v1/iot/devices/{deviceId}/shadow', '设备影子查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/devices/{deviceId}/shadow' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000250, 'GET /api/v1/iot/gateways', '网关查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/gateways' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000251, 'POST /api/v1/iot/gateways', '网关创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/gateways' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000252, 'PUT /api/v1/iot/gateways/{gatewayId}', '网关更新', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/iot/gateways/{gatewayId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000253, 'DELETE /api/v1/iot/gateways/{gatewayId}', '网关删除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'DELETE /api/v1/iot/gateways/{gatewayId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000254, 'GET /api/v1/iot/linkage-rules', '联动规则查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/linkage-rules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000255, 'POST /api/v1/iot/linkage-rules', '联动规则创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/linkage-rules' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000256, 'PUT /api/v1/iot/linkage-rules/{id}', '联动规则更新', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/iot/linkage-rules/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000257, 'DELETE /api/v1/iot/linkage-rules/{id}', '联动规则删除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'DELETE /api/v1/iot/linkage-rules/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000258, 'GET /api/v1/iot/linkage-logs', '联动日志查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/linkage-logs' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000259, 'POST /api/v1/iot/linkage-logs/{linkageNo}/retry', '联动重试', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/linkage-logs/{linkageNo}/retry' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000260, 'GET /api/v1/iot/metrics', '指标字典查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/metrics' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000261, 'POST /api/v1/iot/metrics', '指标字典维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/metrics' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000262, 'GET /api/v1/iot/monitor/consumer-lag', '消费积压监控', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/monitor/consumer-lag' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000263, 'POST /api/v1/iot/products', '产品创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/products' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000264, 'POST /api/v1/iot/products/{productId}/model-sync', '产品物模型同步', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/iot/products/{productId}/model-sync' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000265, 'GET /api/v1/iot/products', '产品查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/products' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000266, 'GET /api/v1/iot/products/{productId}', '产品详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/products/{productId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000267, 'PUT /api/v1/iot/products/{productId}/commands', '产品命令注册', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/iot/products/{productId}/commands' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000268, 'GET /api/v1/iot/products/{productId}/commands', '产品命令查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/products/{productId}/commands' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000269, 'PUT /api/v1/iot/products/{productId}/metric-mappings', '产品指标映射', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/iot/products/{productId}/metric-mappings' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000270, 'GET /api/v1/iot/products/{productId}/metric-mappings', '产品指标映射查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/products/{productId}/metric-mappings' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000271, 'GET /api/v1/iot/quality/stats', '质量统计', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/quality/stats' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000272, 'GET /api/v1/iot/quality/device-usage', '设备利用率', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/quality/device-usage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000273, 'GET /api/v1/iot/telemetry/series', '遥测序列查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/telemetry/series' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000274, 'GET /api/v1/iot/telemetry/latest', '遥测最新值', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/iot/telemetry/latest' AND deleted = 0);

-- ---- A.9 ward 域（18 点；豁免哨兵 infusion-board/{wardId}）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000275, 'POST /api/v1/ward/cold-chain/archives', '冷链档案创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/cold-chain/archives' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000276, 'GET /api/v1/ward/cold-chain/archives', '冷链档案查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/cold-chain/archives' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000277, 'GET /api/v1/ward/cold-chain/archives/{archiveNo}', '冷链档案详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/cold-chain/archives/{archiveNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000278, 'PUT /api/v1/ward/cold-chain/archives/{archiveNo}', '冷链档案维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'PUT /api/v1/ward/cold-chain/archives/{archiveNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000279, 'DELETE /api/v1/ward/cold-chain/archives/{archiveNo}', '冷链档案删除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'DELETE /api/v1/ward/cold-chain/archives/{archiveNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000280, 'POST /api/v1/ward/cold-chain/archives/{archiveNo}/records', '冷链记录登记', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/cold-chain/archives/{archiveNo}/records' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000281, 'GET /api/v1/ward/cold-chain/archives/{archiveNo}/records', '冷链记录查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/cold-chain/archives/{archiveNo}/records' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000282, 'GET /api/v1/ward/infusion-history/{deviceId}', '输注历史查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/infusion-history/{deviceId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000283, 'GET /api/v1/ward/vital-board/{wardId}', '病区生命体征板', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/vital-board/{wardId}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000284, 'POST /api/v1/ward/ward-calls', '呼叫发起', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000285, 'GET /api/v1/ward/ward-calls', '呼叫查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/ward-calls' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000286, 'GET /api/v1/ward/ward-calls/{callNo}', '呼叫详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ward/ward-calls/{callNo}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000287, 'POST /api/v1/ward/ward-calls/{callNo}/answer', '呼叫应答', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls/{callNo}/answer' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000288, 'POST /api/v1/ward/ward-calls/{callNo}/progress', '呼叫处理推进', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls/{callNo}/progress' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000289, 'POST /api/v1/ward/ward-calls/{callNo}/complete', '呼叫完成', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls/{callNo}/complete' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000290, 'POST /api/v1/ward/ward-calls/{callNo}/transfer', '呼叫转接', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls/{callNo}/transfer' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000291, 'POST /api/v1/ward/ward-calls/{callNo}/route', '呼叫路由', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls/{callNo}/route' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000292, 'POST /api/v1/ward/ward-calls/{callNo}/cancel', '呼叫取消', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/ward/ward-calls/{callNo}/cancel' AND deleted = 0);

-- ---- A.10 integration 域（10 点，全部 ADMIN 专属不种业务绑定——V1117 按附件 A 授予角色空列跳过）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000293, 'GET /api/v1/integration/dead-letters', '死信查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/integration/dead-letters' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000294, 'GET /api/v1/integration/dead-letters/{id}', '死信详情', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/integration/dead-letters/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000295, 'POST /api/v1/integration/dead-letters/{id}/replay', '死信重放', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/integration/dead-letters/{id}/replay' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000296, 'POST /api/v1/integration/dead-letters/{id}/close', '死信关闭', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/integration/dead-letters/{id}/close' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000297, 'GET /api/v1/integration/event-publications', '事件发布查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/integration/event-publications' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000298, 'GET /api/v1/integration/event-registry', '事件注册表查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/integration/event-registry' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000299, 'GET /api/v1/integration/mdm-subscriptions', '主数据订阅查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/integration/mdm-subscriptions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000300, 'POST /api/v1/integration/mdm-subscriptions', '主数据订阅维护', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'POST /api/v1/integration/mdm-subscriptions' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000301, 'DELETE /api/v1/integration/mdm-subscriptions/{id}', '主数据订阅删除', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'DELETE /api/v1/integration/mdm-subscriptions/{id}' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116000000000000302, 'GET /api/v1/integration/received-events', '入站事件查询', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/integration/received-events' AND deleted = 0);

-- ---------------------------------------------------------------- 3. MENU 权限点 32 点（附件 B 逐行，perm_type='MENU'）
-- 前端路由守卫 hasRoutePermission 消费面（D2 双命名空间）；ELEMENT 第三命名空间归 F 册后续演进。
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000001, 'patient:archive:create', '患者建档菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'patient:archive:create' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000002, 'patient:archive:search', '患者检索菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'patient:archive:search' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000003, 'billing:charge:settle', '收费结算菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:charge:settle' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000004, 'billing:refund:approve', '退费审批菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:refund:approve' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000005, 'billing:statement:daily-list', '日结单菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'billing:statement:daily-list' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000006, 'pharmacy:drug:maintain', '药品维护菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:drug:maintain' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000007, 'pharmacy:dispense:issue', '门诊摆药菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:dispense:issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000008, 'pharmacy:dispense:return', '门诊退药菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:dispense:return' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000009, 'pharmacy:review:audit', '审方菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:review:audit' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000010, 'pharmacy:dispense:inpatient', '住院摆药菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'pharmacy:dispense:inpatient' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000011, 'outpatient:registration:register', '预约挂号菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:registration:register' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000012, 'outpatient:triage:manage', '门诊分诊菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:triage:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000013, 'outpatient:doctor:consult', '医生接诊菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'outpatient:doctor:consult' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000014, 'nursing:ward:view', '病区看板菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:ward:view' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000015, 'nursing:execution:perform', '护理执行菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:execution:perform' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000016, 'nursing:adverse-event:report', '不良事件菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:adverse-event:report' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000017, 'nursing:pda:use', 'PDA 入口', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'nursing:pda:use' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000018, 'inpatient:admission:manage', '入院管理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:admission:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000019, 'inpatient:bed:view', '床位图菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:bed:view' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000020, 'inpatient:station:view', '护士站菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:station:view' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000021, 'inpatient:transfer:check', '转科核对菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:transfer:check' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000022, 'inpatient:discharge:manage', '出院管理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'inpatient:discharge:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000023, 'iot:product:manage', '产品管理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:product:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000024, 'iot:device:manage', '设备管理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:device:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000025, 'iot:binding:manage', '绑定管理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:binding:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000026, 'iot:alarm-rule:manage', '告警规则菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:alarm-rule:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000027, 'iot:command:issue', '命令下发菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:command:issue' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000028, 'iot:linkage:manage', '联动规则菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:linkage:manage' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000029, 'iot:quality:view', '质量看板菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'iot:quality:view' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000030, 'ward:infusion:view', '输注板菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'ward:infusion:view' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000031, 'ward:call:handle', '呼叫处理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'ward:call:handle' AND deleted = 0);
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1116001000000000032, 'ward:coldchain:manage', '冷链管理菜单', 'MENU'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'ward:coldchain:manage' AND deleted = 0);

-- ---------------------------------------------------------------- 4. 计数自证
-- 本文件登记：API INSERT 302 段 + MENU INSERT 32 段 + UPDATE 6 条；
--   grep -c "INSERT INTO system\.sys_permission" 本文件 = 334（302+32；注释内点号转义避免自匹配）。
-- 净效果：sys_permission 存量 6 点改码 + 新增 328 行（296 API 新点 + 32 MENU 点）= 全量 334 权限点。
