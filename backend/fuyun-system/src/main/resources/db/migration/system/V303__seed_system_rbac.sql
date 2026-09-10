-- V303：RBAC 种子数据（M01 Spec §5 / BRIEF-PR3-01 §2.6）——ADMIN 角色 + P0 权限点 + admin 账号与员工。
-- 幂等形态：INSERT ... WHERE NOT EXISTS（版化迁移只跑一次，防人工重放重复插入，同 integration V5 先例）。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认 'system'
--   （backend 宪法 A.4.2-9）；updated_at 另由 V300 各表触发器统一维护。
-- ID 取值说明：种子行取小整数 ID（跨表 ID 空间独立，且雪花 ID 为 19 位量级永不冲突），供关联行显式引用。

-- ---------------------------------------------------------------- 1. 内置角色：系统管理员（数据范围 ALL）
INSERT INTO system.sys_role (id, role_code, role_name, data_scope_type, status, remark)
SELECT 1, 'ADMIN', '系统管理员', 'ALL', 'ACTIVE', 'P0 内置超管角色：绑定全部 P0 权限点'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role WHERE role_code = 'ADMIN');

-- ---------------------------------------------------------------- 2. P0 权限点（perm_code = API 路径，M01 FU-M01-03）
-- 范围口径：仅登记受保护端点；login/refresh/logout 为免认证端点不登记（BRIEF-PR3-01 §2.6）。
-- 权限点 1：字典发布版本读取（契约型读，workstation 全业务共享）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1, '/api/v1/system/dicts/{type}', '字典发布版本读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '/api/v1/system/dicts/{type}');

-- 权限点 2：字典类型创建
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 2, '/api/v1/system/dict-types', '字典类型创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '/api/v1/system/dict-types');

-- 权限点 3：字典版本创建
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 3, '/api/v1/system/dict-types/{typeCode}/versions', '字典版本创建', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '/api/v1/system/dict-types/{typeCode}/versions');

-- 权限点 4：字典条目新增（仅 DRAFT 版本可改）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 4, '/api/v1/system/dict-versions/{versionId}/items', '字典条目新增', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '/api/v1/system/dict-versions/{versionId}/items');

-- 权限点 5：字典版本发布
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 5, '/api/v1/system/dict-versions/{versionId}/publish', '字典版本发布', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '/api/v1/system/dict-versions/{versionId}/publish');

-- 权限点 6：执业授权校验骨架端点（真实校验随 P1 practice_grant 启用）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 6, '/api/v1/system/practice/check', '执业授权校验', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = '/api/v1/system/practice/check');

-- ---------------------------------------------------------------- 3. ADMIN → 全部 P0 权限点绑定
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission WHERE role_id = 1 AND permission_id = 1);

INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 2, 1, 2
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission WHERE role_id = 1 AND permission_id = 2);

INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 3, 1, 3
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission WHERE role_id = 1 AND permission_id = 3);

INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 4, 1, 4
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission WHERE role_id = 1 AND permission_id = 4);

INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 5, 1, 5
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission WHERE role_id = 1 AND permission_id = 5);

INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 6, 1, 6
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission WHERE role_id = 1 AND permission_id = 6);

-- ---------------------------------------------------------------- 4. admin 登录账号
-- P0 联调初始口令：Fuyun@2026（仅存 bcrypt 哈希，禁明文入库）。
-- 红线注记：该初始口令仅具 dev/test 联调意义——P1 密码策略交付时必须强制改密并评估禁用此种子
--   （BRIEF-PR3-01 §2.6）；P0 无生产部署（计划 §4），风险可控。
INSERT INTO system.sys_user (id, login_name, password_hash, user_type, status)
SELECT 1, 'admin', '$2a$10$mySbYjh9bHhK7WDdnoKvtOHx.gU.z9I4fbsKfyomOyvh.9zlt/FjW', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user WHERE login_name = 'admin');

-- ---------------------------------------------------------------- 5. admin 员工档案（与账号一对一）
INSERT INTO system.sys_employee (id, user_id, emp_no, emp_name, title, primary_org_id, status)
SELECT 1, 1, 'ADMIN', '系统管理员', '系统管理员', NULL, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_employee WHERE emp_no = 'ADMIN');

-- ---------------------------------------------------------------- 6. admin 账号 → ADMIN 角色绑定
INSERT INTO system.sys_user_role (id, user_id, role_id)
SELECT 1, 1, 1
WHERE NOT EXISTS (SELECT 1 FROM system.sys_user_role WHERE user_id = 1 AND role_id = 1);
