-- V1124：组织机构清单读端点 API 权限点 + 六业务角色共享读绑定（全站「暖纸卷宗」重设计切片）——
--   sys_permission 增量 1 点（API）+ sys_role_permission 增量 6 行。
-- 背景：GET /api/v1/system/orgs 受 RbacMatrixIT 第①组「全量端点登记对照」D4 完整性门禁约束
--   （新增端点未登记权限点即红，豁免清单不扩），照 V1122 增量种子先例补登记。
-- 登记计数：API 权限点 1（GET /api/v1/system/orgs）。
-- ID 取值：API 点 1124000000000000001（19 位内固定值，1124 前缀分段防撞，接 V1123 的 1123 段位
--   顺延）；绑定行 1124001000000000000+序（sys_role_permission 表内分段防撞，V1117 API 绑定段
--   「段前缀 + row_number() OVER ()」同款——窗口函数在 NOT EXISTS 过滤后编号，重放零行插入不消耗 id）。
-- perm_code 形态：API 型 = 动词+空格+路径模板（D1/D2 裁定），与 OrgController GET 路径逐字一致（无模板变量）。
-- 绑定口径：六业务角色共享读面（DOCTOR/NURSE/PHARMACIST/CASHIER/REGISTRAR/IOT_ADMIN）——
--   V1117 A.1「字典读共享面 6 角色」同款先例（病区/科室下拉为全工作站公共词表面，非域专属）；
--   ADMIN 不种绑定行（D3 运行期一票放行自动可见，V1117 全段 ADMIN 零行口径）。
--   RbacMatrixIT 第⑤组绑定快照期望面已同步纳入本文件 VALUES 全集（V1121 加入时的扩面先例）。
-- 幂等形态：权限点 INSERT ... SELECT ... WHERE NOT EXISTS ... AND deleted = 0（V1122 先例）；
--   绑定经 VALUES 映射表 join 双表按 role_code/perm_code 解析 id + NOT EXISTS 按 role_id+
--   permission_id 对防重（V1117/V1121 先例），重放零重复零唯一冲突。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认
--   'system'（backend 宪法 A.4.2-9）；updated_at 另由 V300 触发器统一维护。

-- ---------------------------------------------------------------- 1. API 权限点（1 行）
INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1124000000000000001, 'GET /api/v1/system/orgs', '组织机构清单读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/system/orgs' AND deleted = 0);

-- ---------------------------------------------------------------- 2. 共享读绑定（6 行）
INSERT INTO system.sys_role_permission (id, role_id, permission_id)
SELECT 1124001000000000000 + row_number() OVER (),
       r.id, p.id
FROM (VALUES
    ('DOCTOR', 'GET /api/v1/system/orgs'),
    ('NURSE', 'GET /api/v1/system/orgs'),
    ('PHARMACIST', 'GET /api/v1/system/orgs'),
    ('CASHIER', 'GET /api/v1/system/orgs'),
    ('REGISTRAR', 'GET /api/v1/system/orgs'),
    ('IOT_ADMIN', 'GET /api/v1/system/orgs')
) AS m(role_code, perm_code)
JOIN system.sys_role r ON r.role_code = m.role_code AND r.deleted = 0
JOIN system.sys_permission p ON p.perm_code = m.perm_code AND p.deleted = 0
WHERE NOT EXISTS (SELECT 1 FROM system.sys_role_permission rp
                  WHERE rp.role_id = r.id AND rp.permission_id = p.id AND rp.deleted = 0);

-- ---------------------------------------------------------------- 3. 计数自证
-- 本文件装配：sys_permission API 点 1 行 + sys_role_permission 绑定 6 行。
--   绑定行数自证口径：grep -c "^    ('" 本文件 = 6（行首四空格锚定 VALUES 数据行，V1117/V1121 同款）。
-- 净效果：GET /api/v1/system/orgs 过 RbacMatrixIT 第①组登记断言；六业务角色会话均可读病区/科室
--   清单（演示账号族 nursedemo/registrardemo 等的前端下拉真数据链路随之打通）。
