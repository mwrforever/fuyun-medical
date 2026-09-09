package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.UserRoleEntity;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.UserRoleMapper;
import com.fuyun.system.service.IRoleService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色服务实现（system.sys_role 数据访问，登录会话角色摘要查询执行点）。
 *
 * <p>findRoleCodesByUserId 走两步单表查询（P0 无连表 XML，简报 §3.1 明示）：先查
 * sys_user_role 绑定（副表，经 Wrappers 静态工厂，宪法 A.4.3-13），再按绑定批量查
 * sys_role 编码投影（主表，this.lambdaQuery 链式）。只读查询方法级只读事务（A.4.2-7）。
 *
 * <p>装配说明：com.fuyun.system 不在组件扫描范围，Bean 注册点为 SystemWebConfig @Import。
 */
@Slf4j
public class RoleServiceImpl extends ServiceImpl<RoleMapper, RoleEntity> implements IRoleService {

    /** 用户-角色绑定数据访问：findRoleCodesByUserId 第一步的副表查询载体 */
    private final UserRoleMapper userRoleMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param userRoleMapper 绑定表 mapper，非空；来源：同模块 mapper 包
     */
    public RoleServiceImpl(UserRoleMapper userRoleMapper) {
        this.userRoleMapper = userRoleMapper;
    }

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
}
