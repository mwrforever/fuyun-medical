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
}
