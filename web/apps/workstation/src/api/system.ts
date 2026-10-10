/**
 * 系统管理域 API（web A.3-5 模块化）：权限管理台四端点（PR-4F Task 4/5 契约消费面）——
 * 角色清单（含各角色绑定码集）/ 权限点分组清单 / 角色权限矩阵全量覆写 / 角色启停；
 * 组织机构清单与字典条目两读端点（暖纸卷宗重设计切片——病区/科室下拉与字典消费的真数据面，
 * 替代各 api 模块 WARD_OPTIONS 与门诊视图 DEPT-INT 假常量）。路径前缀 /v1/system/**（baseURL
 * 已含 /api）；类型一律取 @fuyun/shared 生成物（openapi-typescript 经 gen:api 同源产出，禁手写 paths）。
 */
import { http } from './http';
import type { components } from '@fuyun/shared/api';

/** 契约类型别名（生成物唯一来源，A.3-3）：角色管理视图（roleCode/roleName/status/permCodes） */
export type RoleAdminVO = components['schemas']['RoleAdminVO'];
/** 契约类型别名（生成物唯一来源，A.3-3）：权限点分组视图（permType + points 明细） */
export type PermissionGroupVO = components['schemas']['PermissionGroupVO'];
/** 契约类型别名（生成物唯一来源，A.3-3）：组织机构视图（orgCode/orgName/orgType/sort，id 字符串化） */
export type OrgVO = components['schemas']['OrgVO'];
/** 契约类型别名（生成物唯一来源，A.3-3）：字典版本视图（版本号 + 条目全量清单） */
export type DictVersionVO = components['schemas']['DictVersionVO'];
/** 契约类型别名（生成物唯一来源，A.3-3）：字典条目视图（itemCode/itemName/sort） */
export type DictItemVO = components['schemas']['DictItemVO'];

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

/**
 * 查询启用状态组织机构清单（病区/科室下拉真数据源，后端按 sort 升序返回）。
 * 消费面：workstation 病区/科室选择器——替代 nursing/inpatient/iot 各 api 模块 WARD_OPTIONS
 * 与门诊视图 DEPT-INT 硬编码选项（暖纸卷宗重设计切片）。
 *
 * @param query 查询参数对象：type=机构类型，WARD=病区 / DEPT=科室
 *              （非法值后端 SYS-1031/400 拒绝，经拦截器统一弹错）
 * @return 启用机构清单（sort 升序；id/parentId 为字符串化雪花 ID）；空清单=无启用机构
 */
export async function listOrgs(query: { type: 'WARD' | 'DEPT' }): Promise<OrgVO[]> {
  const resp = await http.get<OrgVO[]>('/v1/system/orgs', { params: query });
  return resp.data;
}

/**
 * 按字典类型编码读取已发布版本的全部条目（字典消费面薄封装）。
 * 后端契约型读返回版本+条目聚合视图（Cache-Control: no-cache 每次回源），本函数直取
 * items 段交调用方；条目缺席兜底空数组，视图层免判空。
 *
 * @param typeCode 字典类型编码（路径参数，如 gender）；类型不存在后端 SYS-1011/404
 * @return 条目清单（sort 升序）；无条目返回空数组
 */
export async function listDictItems(typeCode: string): Promise<DictItemVO[]> {
  const resp = await http.get<DictVersionVO>(`/v1/system/dicts/${typeCode}`);
  return resp.data.items ?? [];
}
