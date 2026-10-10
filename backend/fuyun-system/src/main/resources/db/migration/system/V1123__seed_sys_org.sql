-- V1123：sys_org 组织机构种子（全站「暖纸卷宗」重设计·组织清单真数据源切片）——病区 2 + 科室 3。
-- 背景：GET /api/v1/system/orgs 读端点（OrgController）落地，替代 workstation 各 api 模块
--   WARD_OPTIONS 与门诊视图 DEPT-INT 前端假常量——种子行必须对齐既有演示链路（调研口径）：
--   ①病区 W01：org_code 对齐 nursing.nursing_ward_config V801 演示种子（ward_id='W01'，表注释
--     明言「病区编码 = M01 组织机构病区 code」）与 nursing/inpatient/iot 前端 WARD_OPTIONS 常量；
--   ②病区 1001：org_code 对齐 ward/iot 域 Long 病区链路（ward.ts WARD_OPTIONS='1001'、
--     RbacMatrixIT 哨兵 wardId=1001、bigscreen board 主题病区段——数字形态演示病区）；
--   ③科室 DEPT-INT/DEPT-SUR/DEPT-PED：对齐门诊域演示链路（TriageBoardView 诊区选项
--     内科/外科/儿科 + bigscreen QueueBoardView 默认 dept=DEPT-INT）。
-- 树形口径：P0 邻接表仅 parent_id 表达（V300 头注），演示种子五行为平级根节点（parent_id NULL），
--   院区/树形归属随 P1 组织管理补，不猜业务语义。
-- ID 取值：机构行 1123000000000000001~0005（19 位内固定值，1123 前缀分段防撞，接 V1122 的 1122
--   段位顺延）；sys_org 表内独立主键空间。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认
--   'system'（backend 宪法 A.4.2-9）；updated_at 另由 V300 触发器统一维护。
-- 幂等形态：INSERT ... SELECT ... WHERE NOT EXISTS ... AND deleted = 0（V1122 先例，与
--   uk_sys_org_org_code 部分唯一索引同口径，重放零重复零唯一冲突）。

-- ---------------------------------------------------------------- 病区（WARD，启用）
-- W01 演示病区：nursing V801 配置锚 + 前端 WARD_OPTIONS[W01] 演示链路（sort=1 首选项）
INSERT INTO system.sys_org (id, org_code, org_name, org_type, org_attr, parent_id, sort, status)
SELECT 1123000000000000001, 'W01', '演示病区', 'WARD', 'CLINICAL', NULL, 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_org WHERE org_code = 'W01' AND deleted = 0);

-- 1001 演示病区：ward/iot 域 Long 病区链路（ward.ts WARD_OPTIONS[1001] / 哨兵 wardId=1001）
INSERT INTO system.sys_org (id, org_code, org_name, org_type, org_attr, parent_id, sort, status)
SELECT 1123000000000000002, '1001', '演示病区', 'WARD', 'CLINICAL', NULL, 2, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_org WHERE org_code = '1001' AND deleted = 0);

-- ---------------------------------------------------------------- 科室（DEPT，启用）
-- DEPT-INT 内科：门诊域演示主链路（分诊台默认诊区 + bigscreen 候诊榜默认 dept）
INSERT INTO system.sys_org (id, org_code, org_name, org_type, org_attr, parent_id, sort, status)
SELECT 1123000000000000003, 'DEPT-INT', '内科', 'DEPT', 'CLINICAL', NULL, 1, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_org WHERE org_code = 'DEPT-INT' AND deleted = 0);

-- DEPT-SUR 外科：分诊台诊区选项演示链路
INSERT INTO system.sys_org (id, org_code, org_name, org_type, org_attr, parent_id, sort, status)
SELECT 1123000000000000004, 'DEPT-SUR', '外科', 'DEPT', 'CLINICAL', NULL, 2, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_org WHERE org_code = 'DEPT-SUR' AND deleted = 0);

-- DEPT-PED 儿科：分诊台诊区选项演示链路
INSERT INTO system.sys_org (id, org_code, org_name, org_type, org_attr, parent_id, sort, status)
SELECT 1123000000000000005, 'DEPT-PED', '儿科', 'DEPT', 'CLINICAL', NULL, 3, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_org WHERE org_code = 'DEPT-PED' AND deleted = 0);
