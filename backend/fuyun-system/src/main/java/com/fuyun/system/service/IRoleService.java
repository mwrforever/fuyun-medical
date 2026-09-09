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
}
