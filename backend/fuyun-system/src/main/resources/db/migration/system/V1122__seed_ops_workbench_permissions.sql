-- V1122：M19 运营工作台 API 权限点种子（批次 2 册 2 后端聚合切片）——sys_permission 增量 2 点（API）。
-- 背景：两新端点受 RbacMatrixIT 第①组「全量端点登记对照」D4 完整性门禁约束（新增端点未登记
--   权限点即红，豁免清单不扩），照 V1116 全量种子 / V1120 增量种子先例补登记。
-- 登记计数：API 权限点 2（GET /api/v1/ops/workbench/overview 与 GET /api/v1/ops/workbench/events）。
-- ID 取值：API 点 1122000000000000001+序（19 位内固定值，1122 前缀分段防撞，接 V1120 的 112 段位
--   顺延、版本接 V1121）；后续 M19 驾驶舱权限域按本段位续号。
-- perm_code 形态：API 型 = 动词+空格+路径模板（D1/D2 裁定），与 OpsWorkbenchController 两 GET
--   路径逐字一致（无模板变量）。
-- 绑定口径：两码为运营分析只读面 ADMIN 专属码不种绑定行（F5 先例——ADMIN 运行期一票放行自动
--   可见；业务角色绑定随 M19 驾驶舱权限域后续批次按角色矩阵展开，本切片不猜业务语义）。
-- 幂等形态：INSERT ... SELECT ... WHERE NOT EXISTS ... AND deleted = 0（V303/V1116/V1120 先例，
--   与 perm_code 部分唯一索引 uk_sys_permission_perm_code 同口径，重放零重复零唯一冲突）。
-- 审计口径：created_at/updated_at 由数据库 DEFAULT now() 维护、created_by/updated_by 取默认
--   'system'（backend 宪法 A.4.2-9）；updated_at 另由 V300 触发器统一维护。

INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1122000000000000001, 'GET /api/v1/ops/workbench/overview', '运营工作台总览快照读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ops/workbench/overview' AND deleted = 0);

INSERT INTO system.sys_permission (id, perm_code, perm_name, perm_type)
SELECT 1122000000000000002, 'GET /api/v1/ops/workbench/events', '运营工作台事件流读取', 'API'
WHERE NOT EXISTS (SELECT 1 FROM system.sys_permission WHERE perm_code = 'GET /api/v1/ops/workbench/events' AND deleted = 0);
