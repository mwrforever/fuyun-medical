package com.fuyun.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.system.entity.RoleEntity;
import java.util.List;

/**
 * 角色服务（system.sys_role 数据访问与用户角色摘要查询入口，BRIEF-PR3-01 §3.1）。
 *
 * <p>CRUD 型接口继承 IService（宪法 A.4.3-20）；P0 仅提供登录会话组装所需的角色编码查询，
 * 完整角色管理 CRUD 属 P0 明确不做范围（简报 §0）。
 */
public interface IRoleService extends IService<RoleEntity> {

    /**
     * 查询用户被授予且处于启用状态的角色编码清单（登录会话角色摘要的数据源）。
     *
     * <p>两步单表查询（P0 无 XML）：sys_user_role 绑定 → sys_role 编码投影；
     * 停用（DISABLED）角色不入会话（停用角色的权限语义即失效）。
     *
     * @param userId 用户 ID，非空；来源：登录认证通过后的 sys_user.id
     * @return 角色编码清单（如 ["ADMIN"]）；无绑定或绑定角色全部停用时为空清单，非 null
     */
    List<String> findRoleCodesByUserId(Long userId);

    /**
     * 查询用户角色展开后的授权点编码清单（登录会话 permissions 填实的数据源，PR-4D）。
     *
     * <p>执行流程（四步单表查询，禁连表禁 N+1）：复用 {@link #findRoleCodesByUserId(Long)} 取启用
     * 角色码——含 ADMIN 即特判短路（D3 裁定）：直接 sys_permission 全表投影 perm_code 返回，
     * 不触绑定查询；否则 sys_role 按 code IN 复查启用角色 id → sys_role_permission 按
     * role_id IN 批量取 permission_id → sys_permission 按 id IN 投影 perm_code。
     *
     * <p>命名空间口径：MENU+API 全命名空间导出（前端路由守卫与后续元素级控制共源，
     * F 册 ELEMENT 命名空间同源扩展）；ADMIN 语义=运行期全放（403 拦截器一票放行同锚），
     * 本方法的 ADMIN 分支仅为前端可见性导出全量码。
     *
     * @param userId 用户 ID，非空；来源：登录认证通过后的 sys_user.id
     * @return 授权点编码清单（ADMIN=全表 perm_code，含 MENU）；无绑定/绑定角色全部停用/
     *         角色无权限绑定时为空清单，非 null
     */
    List<String> findPermissionCodesByUserId(Long userId);
}
