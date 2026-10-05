package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.entity.UserRoleEntity;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import com.fuyun.system.mapper.UserRoleMapper;
import com.fuyun.system.service.IRoleService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色服务实现（system.sys_role 数据访问，登录会话角色摘要与权限集展开查询执行点）。
 *
 * <p>findRoleCodesByUserId 走两步单表查询（P0 无连表 XML，简报 §3.1 明示）：先查
 * sys_user_role 绑定（副表，经 Wrappers 静态工厂，宪法 A.4.3-13），再按绑定批量查
 * sys_role 编码投影（主表，this.lambdaQuery 链式）。findPermissionCodesByUserId（PR-4D）
 * 复用角色摘要后走四步单表链展开授权点（ADMIN 特判全表导出，D3 裁定）。只读查询
 * 方法级只读事务（A.4.2-7）。
 *
 * <p>装配说明：com.fuyun.system 不在组件扫描范围，Bean 注册点为 SystemWebConfig @Import。
 */
@Slf4j
public class RoleServiceImpl extends ServiceImpl<RoleMapper, RoleEntity> implements IRoleService {

    /** 用户-角色绑定数据访问：findRoleCodesByUserId 第一步的副表查询载体 */
    private final UserRoleMapper userRoleMapper;

    /** 权限点数据访问：findPermissionCodesByUserId 的 ADMIN 全表投影与末步 id IN 投影载体 */
    private final PermissionMapper permissionMapper;

    /** 角色-权限绑定数据访问：findPermissionCodesByUserId 第三步批量取 permission_id 载体 */
    private final RolePermissionMapper rolePermissionMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param userRoleMapper        绑定表 mapper，非空；来源：同模块 mapper 包
     * @param permissionMapper      权限点 mapper，非空；来源：同模块 mapper 包（PR-4D 权限集展开）
     * @param rolePermissionMapper  绑定关系 mapper，非空；来源：同模块 mapper 包
     */
    public RoleServiceImpl(
            UserRoleMapper userRoleMapper,
            PermissionMapper permissionMapper,
            RolePermissionMapper rolePermissionMapper) {
        this.userRoleMapper = userRoleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    /**
     * 查询用户被授予且处于启用状态的角色编码清单（登录会话角色摘要的数据源）。
     *
     * <p>执行流程（两步单表查询，P0 无连表 XML）：sys_user_role 绑定表精确投影取 role_id →
     * 按绑定批量查 sys_role，仅取启用（ACTIVE）角色的编码投影——停用角色的权限语义即失效，
     * 不入会话。第一步结果为空时短路返回，不再触达主表。
     *
     * @param userId 用户 ID，非空；来源：登录认证通过后的 sys_user.id
     * @return 启用角色编码清单（如 ["ADMIN"]）；无绑定或绑定角色全部停用时为空清单，非 null
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> findRoleCodesByUserId(Long userId) {
        // 第一步：绑定表精确投影（select 仅取 role_id，A.4.3-14）
        List<Long> roleIds = userRoleMapper
                .selectList(Wrappers.<UserRoleEntity>lambdaQuery()
                        .eq(UserRoleEntity::getUserId, userId)
                        .select(UserRoleEntity::getRoleId))
                .stream()
                .map(UserRoleEntity::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        // 第二步：角色表编码投影，仅取启用角色（停用角色的权限语义失效，不入会话）
        List<String> roleCodes = this.lambdaQuery()
                .in(RoleEntity::getId, roleIds)
                .eq(RoleEntity::getStatus, RoleStatus.ACTIVE)
                .select(RoleEntity::getRoleCode)
                .list()
                .stream()
                .map(RoleEntity::getRoleCode)
                .toList();
        log.debug("用户角色摘要查询完成：userId={}，roleCodes={}", userId, roleCodes);
        return roleCodes;
    }

    /**
     * 查询用户角色展开后的授权点编码清单（登录会话 permissions 填实，PR-4D）。
     *
     * <p>执行流程（四步单表查询，禁连表禁 N+1）：①复用 {@link #findRoleCodesByUserId(Long)}
     * 取启用角色码——含 ADMIN 即特判短路（D3 裁定）：sys_permission 全表投影 perm_code
     * （含 MENU，前端侧栏可见性依赖；deleted=0 由 @TableLogic 自动携带）返回，不触绑定查询；
     * ②否则 sys_role 按 code IN 复查启用角色 id（主表链式查询，A.4.3-13）③sys_role_permission
     * 按 role_id IN 批量取 permission_id（副表 Wrappers）④sys_permission 按 id IN 投影
     * perm_code 并去重（多角色共绑同一权限点时授权集语义收敛）。MENU+API 全命名空间导出
     * （前端路由守卫与后续 F 册元素级控制共源）。
     *
     * @param userId 用户 ID，非空；来源：登录认证通过后的 sys_user.id
     * @return 授权点编码清单（ADMIN=全表 perm_code，含 MENU）；无绑定/绑定角色全部停用/
     *         角色无权限绑定时为空清单，非 null
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> findPermissionCodesByUserId(Long userId) {
        // 第一步：复用角色摘要查询（启用过滤语义同源）；ADMIN 特判短路，不触绑定查询（D3 裁定）
        List<String> roleCodes = findRoleCodesByUserId(userId);
        if (roleCodes.contains(SecurityConstants.ADMIN_ROLE_CODE)) {
            List<String> allCodes =
                    permissionMapper
                            .selectList(Wrappers.<PermissionEntity>lambdaQuery().select(PermissionEntity::getPermCode))
                            .stream()
                            .map(PermissionEntity::getPermCode)
                            .toList();
            log.debug("ADMIN 权限全表导出：userId={}，permCodeCount={}", userId, allCodes.size());
            return allCodes;
        }
        if (roleCodes.isEmpty()) {
            return List.of();
        }
        // 第二步：主表按角色码复查启用角色 id（code→id 桥接绑定链；会话期内角色停用的防御面由空集短路承载）
        List<Long> roleIds = this.lambdaQuery()
                .in(RoleEntity::getRoleCode, roleCodes)
                .eq(RoleEntity::getStatus, RoleStatus.ACTIVE)
                .select(RoleEntity::getId)
                .list()
                .stream()
                .map(RoleEntity::getId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        // 第三步：绑定关系表按 role_id IN 批量取 permission_id（循环内禁 N+1，A.4.3-14）
        List<Long> permissionIds = rolePermissionMapper
                .selectList(Wrappers.<RolePermissionEntity>lambdaQuery()
                        .in(RolePermissionEntity::getRoleId, roleIds)
                        .select(RolePermissionEntity::getPermissionId))
                .stream()
                .map(RolePermissionEntity::getPermissionId)
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        // 第四步：权限表按 id IN 投影 perm_code，distinct 收敛多角色共绑的重复授权点
        List<String> permCodes = permissionMapper
                .selectList(Wrappers.<PermissionEntity>lambdaQuery()
                        .in(PermissionEntity::getId, permissionIds)
                        .select(PermissionEntity::getPermCode))
                .stream()
                .map(PermissionEntity::getPermCode)
                .distinct()
                .toList();
        log.debug("用户权限集展开完成：userId={}，roleCodes={}，permCodeCount={}", userId, roleCodes, permCodes.size());
        return permCodes;
    }
}
