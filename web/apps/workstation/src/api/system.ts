/**
 * 系统管理域 API（web A.3-5 模块化）：权限管理台四端点（PR-4F Task 4/5 契约消费面）——
 * 角色清单（含各角色绑定码集）/ 权限点分组清单 / 角色权限矩阵全量覆写 / 角色启停。
 * 路径前缀 /v1/system/roles**（baseURL 已含 /api）；类型一律取 @fuyun/shared 生成物
 * （openapi-typescript 经 gen:api 同源产出，禁手写 paths）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3）：角色管理视图（roleCode/roleName/status/permCodes） */
export type RoleAdminVO = components['schemas']['RoleAdminVO'];
/** 契约类型别名（生成物唯一来源，A.3-3）：权限点分组视图（permType + points 明细） */
export type PermissionGroupVO = components['schemas']['PermissionGroupVO'];

/**
 * 查询全量角色及各自绑定码集（管理台首屏渲染，契约型读豁免分页）。
 * ADMIN 行 permCodes=空清单——运行期全放语义由前端矩阵编辑器特殊渲染（D3），非数据缺失。
 *
 * @return 角色清单；空清单=无角色（种子异常场景）
 */
export async function listRoles(): Promise<RoleAdminVO[]> {
  const resp = await http.get<RoleAdminVO[]>('/v1/system/roles');
  return resp.data;
}

/**
 * 查询全量权限点按 perm_type 分组的清单（矩阵列全集：API/MENU/ELEMENT 三组）。
 *
 * @return 分组清单（组内 points 含 permCode/permName）；失败由拦截器统一弹错
 */
export async function listPermissions(): Promise<PermissionGroupVO[]> {
  const resp = await http.get<PermissionGroupVO[]>('/v1/system/permissions');
  return resp.data;
}

/**
 * 角色权限矩阵全量覆写（F4 语义：非增量 diff，请求载荷=该角色目标态全部权限码；
 * 后端事务内 diff 删插 + 广播 system.permission.changed 事件触发各实例 403 矩阵重载）。
 *
 * @param roleCode 角色编码（路径参数，非空；ADMIN 由后端拒绝覆写）
 * @param permCodes 全量目标码集（空清单=清空该角色全部绑定，调用方须确认后传入）
 * @return 覆写后角色视图（permCodes=去重保序载荷即落库终态）
 */
export async function updateRolePermissions(
  roleCode: string,
  permCodes: string[],
): Promise<RoleAdminVO> {
  const resp = await http.put<RoleAdminVO>(`/v1/system/roles/${roleCode}/permissions`, {
    permCodes,
  });
  return resp.data;
}

/**
 * 角色启停（管理台角色行启停开关）：停用角色禁配新用户，且停用即踢出该角色全部在线会话
 * （下一请求 401 需重新登录，重登后不再携带该角色）；同值提交后端零写零事件不踢会话。
 *
 * @param roleCode 角色编码（路径参数，非空）
 * @param status 目标态：ACTIVE=启用 / DISABLED=停用（未知值后端 SYS-1031 拒绝）
 * @return 更新后角色视图（permCodes=当前绑定码集实查）
 */
export async function updateRoleStatus(
  roleCode: string,
  status: 'ACTIVE' | 'DISABLED',
): Promise<RoleAdminVO> {
  const resp = await http.put<RoleAdminVO>(`/v1/system/roles/${roleCode}/status`, { status });
  return resp.data;
}
