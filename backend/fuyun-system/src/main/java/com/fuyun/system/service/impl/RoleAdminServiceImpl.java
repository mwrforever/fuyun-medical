package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import com.fuyun.system.service.IRoleAdminService;
import com.fuyun.system.vo.RoleAdminVO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理读服务实现（权限管理台角色清单与绑定码集组装，PR-4F Task 4）。
 *
 * <p>三步单表查询（禁连表禁 N+1，照 RoleServiceImpl.findPermissionCodesByUserId 先例）：
 * ①sys_role 全量（含停用角色——管理台需展示并操作启停；deleted=0 由 @TableLogic 自动携带）
 * ②sys_role_permission 按 role_id IN 批量取绑定对 ③sys_permission 按 id 批量投影 perm_code
 * 后内存按角色分组组装；码集含全部命名空间（API/MENU/ELEMENT 混出，F2 形态 A）。
 * 只读查询方法级只读事务（A.4.2-7）。
 *
 * <p>装配说明：com.fuyun.system 不在组件扫描范围，Bean 注册点为 SystemWebConfig @Import。
 */
@Slf4j
public class RoleAdminServiceImpl implements IRoleAdminService {

    /** 角色数据访问：第一步全量角色投影载体 */
    private final RoleMapper roleMapper;

    /** 角色-权限绑定数据访问：第二步按 role_id IN 批量取绑定对载体 */
    private final RolePermissionMapper rolePermissionMapper;

    /** 权限点数据访问：第三步按 id 批量投影 perm_code 载体 */
    private final PermissionMapper permissionMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param roleMapper           角色 mapper，非空；来源：同模块 mapper 包（@MapperScan 注册）
     * @param rolePermissionMapper 绑定关系 mapper，非空；来源：同上
     * @param permissionMapper     权限点 mapper，非空；来源：同上
     */
    public RoleAdminServiceImpl(
            RoleMapper roleMapper, RolePermissionMapper rolePermissionMapper, PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    /**
     * 查询全量角色及各自绑定的权限点编码清单（权限管理台角色矩阵渲染数据源）。
     *
     * <p>执行流程（三步单表，禁连表）：全量角色投影（含停用角色）→ 绑定对按 role_id IN 批量
     * 装载 → 权限码按 permission_id 批量投影后分组。ADMIN 无绑定行→permCodes=空清单
     * （运行期全放语义由前端按角色码特殊渲染，D3 注记，本方法不展开全表码）。
     *
     * @return 全量角色清单（按角色表返回序）；permCodes=绑定权限码集；无任何角色时为空清单，非 null
     */
    @Override
    @Transactional(readOnly = true)
    public List<RoleAdminVO> listRoles() {
        // 第一步：全量角色精确投影（含停用角色——管理台启停操作面；deleted=0 由 @TableLogic 携带，A.4.3-14）
        List<RoleEntity> roles = roleMapper.selectList(Wrappers.<RoleEntity>lambdaQuery()
                .select(
                        RoleEntity::getId,
                        RoleEntity::getRoleCode,
                        RoleEntity::getRoleName,
                        RoleEntity::getStatus,
                        RoleEntity::getDataScopeType));
        if (roles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = roles.stream().map(RoleEntity::getId).toList();
        // 第二步：绑定对按 role_id IN 批量装载（循环内禁 N+1，A.4.3-14）
        List<RolePermissionEntity> bindings =
                rolePermissionMapper.selectList(Wrappers.<RolePermissionEntity>lambdaQuery()
                        .in(RolePermissionEntity::getRoleId, roleIds)
                        .select(RolePermissionEntity::getRoleId, RolePermissionEntity::getPermissionId));
        if (bindings.isEmpty()) {
            // 全部角色无绑定行：一律空清单直返，不触权限表（ADMIN/未绑定角色同语义承载）
            return roles.stream().map(role -> toVO(role, List.of())).toList();
        }
        // 第三步：权限码按 permission_id 批量投影（空集合会产非法 IN 语句，前置去重装载；
        // selectByIds 为 MP 3.5.17 非过时形态——selectBatchIds 已标 deprecated，宪法 A.1-14）
        Map<Long, String> permCodeById =
                permissionMapper
                        .selectByIds(bindings.stream()
                                .map(RolePermissionEntity::getPermissionId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(PermissionEntity::getId, PermissionEntity::getPermCode));
        // 绑定对按角色分组：permission_id → perm_code 经映射投影，绑定指向已删权限点的残行过滤为 null 丢弃
        Map<Long, List<String>> permCodesByRoleId = bindings.stream()
                .collect(Collectors.groupingBy(
                        RolePermissionEntity::getRoleId,
                        Collectors.mapping(
                                binding -> permCodeById.get(binding.getPermissionId()),
                                Collectors.filtering(Objects::nonNull, Collectors.toList()))));
        List<RoleAdminVO> result = roles.stream()
                .map(role -> toVO(role, permCodesByRoleId.getOrDefault(role.getId(), List.of())))
                .toList();
        log.debug("管理台角色清单查询完成：roleCount={}，bindingCount={}", result.size(), bindings.size());
        return result;
    }

    /**
     * 角色实体 → 管理台出参组装。
     *
     * @param role      角色实体（第一步投影行），非空；status/dataScopeType 列 NOT NULL
     * @param permCodes 已组装的绑定权限码集，非 null；无绑定行为空清单
     * @return 管理台角色出参，非空
     */
    private RoleAdminVO toVO(RoleEntity role, List<String> permCodes) {
        return new RoleAdminVO(
                role.getRoleCode(),
                role.getRoleName(),
                role.getStatus().getCode(),
                role.getDataScopeType().getCode(),
                permCodes);
    }
}
