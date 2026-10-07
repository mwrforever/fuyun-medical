package com.fuyun.system.service;

import com.fuyun.system.vo.RoleAdminVO;
import java.util.List;

/**
 * 角色管理读服务（权限管理台角色清单与绑定码集查询入口，PR-4F Task 4）。
 *
 * <p>聚合/报表型接口（宪法 A.4.3-20）：不继承 IService，实现注入所需 mapper 组装
 * 跨表读视图。读端点与写端点（Task 5 权限矩阵覆写/角色启停）分离——本接口只承载
 * 管理台首屏渲染所需的全量角色 + 各自绑定权限码集清单。
 */
public interface IRoleAdminService {

    /**
     * 查询全量角色及各自绑定的权限点编码清单（权限管理台角色矩阵渲染数据源）。
     *
     * <p>三步单表查询（禁连表，照 RoleServiceImpl 先例）：①sys_role 全量（含停用角色——
     * 管理台需展示并允许操作启停，与登录会话仅取 ACTIVE 的语义分叉）②sys_role_permission
     * 按 role_id IN 批量取 (role_id, permission_id) ③sys_permission 按 id 批量投影 perm_code
     * 后按角色分组组装。码集含全部命名空间（API/MENU/ELEMENT 混出，F2 形态 A）。
     *
     * <p>ADMIN 语义（D3 注记）：内置超管角色不种绑定行，permCodes=空清单；运行期全放语义
     * 由前端按角色码特殊渲染（勾选全显），本方法不在此展开全表码。
     *
     * @return 全量角色清单（按角色表返回序）；permCodes=绑定权限码集（ADMIN/未绑定为空清单），
     *         各清单元素非 null；无任何角色时为空清单，非 null
     */
    List<RoleAdminVO> listRoles();

    /**
     * 角色权限矩阵全量覆写（PUT /api/v1/system/roles/{roleCode}/permissions 执行点，PR-4F Task 5）。
     *
     * <p>执行流程（diff 删插，方法级事务）：①角色码定位（SYS-1041/404）②ADMIN 拒绝
     * （SYS-1043/400——运行期全放语义无绑定行可维护，D3）③载荷去重保序 + 未登记码校验
     * （SYS-1042/400，detail 携带全部非法码）④diff：既有−载荷走逻辑删除、载荷−既有走
     * insert（id 由 ASSIGN_ID 雪花生成，与 V1121 种子号段天然分离）⑤双空（无变化）直接
     * 返回不发事件——幂等保存不空转，无变更不触发刷新广播/会话清理⑥有变更则事务内发布
     * {@code PermissionMatrixChangedEvent}（AFTER_COMMIT 消费归 Task 6 装配）。
     *
     * <p>全量覆写语义（PUT）：载荷即目标态——空清单=清空该角色全部绑定（亦属变更、发事件）；
     * 绑定指向已删权限点的残行在覆写中一并清理（不在载荷 id 集即入删除侧）。
     *
     * @param roleCode  角色编码，非空；来源：管理端路径参数
     * @param permCodes 目标权限码全集（API/MENU/ELEMENT 混出，顺序保留去重），非 null（可为
     *                  空清单=清空绑定）；来源：管理台矩阵编辑器提交
     * @return 覆写后角色视图（permCodes=去重保序的载荷码集，即落库终态）
     * @throws com.fuyun.common.exception.BizException SYS-1041（角色不存在，404）、SYS-1043
     *                      （ADMIN 不可维护，400）、SYS-1042（载荷含未登记权限码，400——
     *                      抛出时事务零写，禁部分生效）
     */
    RoleAdminVO overwritePermissions(String roleCode, List<String> permCodes);

    /**
     * 角色启停（PUT /api/v1/system/roles/{roleCode}/status 执行点，PR-4F Task 5）。
     *
     * <p>执行流程（方法级事务）：校验链同覆写（SYS-1041 角色定位 / SYS-1043 ADMIN 拒绝）
     * + status 值校验（未知值经 RoleStatus.fromCode 收口 SYS-1031/400，既有码复用）→
     * 同值短路（目标态与现态一致时零写零事件直返，重复保存不广播不踢会话——评审 A-I1）→
     * updateById 落启停状态 → 事务内发布 {@code PermissionMatrixChangedEvent}（启停即踢出
     * 该角色全部在线会话、重新登录后按新状态生效——RoleStatus javadoc 口径，刷新广播归
     * Task 6）。
     *
     * @param roleCode 角色编码，非空；来源：管理端路径参数
     * @param status   目标状态 code（ACTIVE/DISABLED），非空；来源：管理端请求体
     * @return 更新后角色视图（status=落库终态，permCodes=当前绑定码集实查）
     * @throws com.fuyun.common.exception.BizException SYS-1041（角色不存在，404）、SYS-1043
     *                      （ADMIN 不可启停，400）、SYS-1031（status 值域外，400）
     */
    RoleAdminVO updateStatus(String roleCode, String status);
}
