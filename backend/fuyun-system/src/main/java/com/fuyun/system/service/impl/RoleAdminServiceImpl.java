package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.internal.PermissionMatrixChangedEvent;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import com.fuyun.system.service.IRoleAdminService;
import com.fuyun.system.vo.RoleAdminVO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理服务实现（权限管理台角色清单读链 + 矩阵覆写/角色启停写链，PR-4F Task 4/5）。
 *
 * <p>读链（Task 4）：三步单表查询（禁连表禁 N+1，照 RoleServiceImpl.findPermissionCodesByUserId
 * 先例）：①sys_role 全量（含停用角色——管理台需展示并操作启停；deleted=0 由 @TableLogic
 * 自动携带）②sys_role_permission 按 role_id IN 批量取绑定对 ③sys_permission 按 id 批量投影
 * perm_code 后内存按角色分组组装；码集含全部命名空间（API/MENU/ELEMENT 混出，F2 形态 A）。
 * 只读查询方法级只读事务（A.4.2-7）。
 *
 * <p>写链（Task 5，方法级写事务）：矩阵全量覆写=载荷去重保序→未登记码校验（SYS-1042 零写
 * 拒绝）→diff 删插（逻辑删除+ASSIGN_ID 插入）→双空幂等短路不发事件；角色启停=值域校验
 * （SYS-1031 复用）+updateById。两写路径均经事务内发布 PermissionMatrixChangedEvent
 * （AFTER_COMMIT 消费归 Task 6），ADMIN 一律 SYS-1043 拒绝（运行期全放语义，D3）。
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

    /** 应用事件发布器：写路径事务内发布 PermissionMatrixChangedEvent（消费面归 Task 6） */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param roleMapper           角色 mapper，非空；来源：同模块 mapper 包（@MapperScan 注册）
     * @param rolePermissionMapper 绑定关系 mapper，非空；来源：同上
     * @param permissionMapper     权限点 mapper，非空；来源：同上
     * @param eventPublisher       Spring 应用事件发布器，非空；写路径事务内发布矩阵变更事件（Task 5）
     */
    public RoleAdminServiceImpl(
            RoleMapper roleMapper,
            RolePermissionMapper rolePermissionMapper,
            PermissionMapper permissionMapper,
            ApplicationEventPublisher eventPublisher) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.eventPublisher = eventPublisher;
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

    @Override
    @Transactional
    public RoleAdminVO overwritePermissions(String roleCode, List<String> permCodes) {
        // 前置校验链：角色码定位（SYS-1041）→ ADMIN 不可维护（SYS-1043）
        RoleEntity role = locateWritableRole(roleCode);
        // 载荷去重保序（{A,A,B} 按 {A,B} 处理）；空载荷跳过登记校验查询（空 IN 为非法语句，读链同防线）
        List<String> distinctCodes = permCodes.stream().distinct().toList();
        Map<String, Long> idByCode = distinctCodes.isEmpty()
                ? Map.of()
                : permissionMapper
                        .selectList(Wrappers.<PermissionEntity>lambdaQuery()
                                .in(PermissionEntity::getPermCode, distinctCodes)
                                .select(PermissionEntity::getId, PermissionEntity::getPermCode))
                        .stream()
                        .collect(Collectors.toMap(PermissionEntity::getPermCode, PermissionEntity::getId));
        // 未登记码校验（SYS-1042）：detail 携带全部非法码——全量覆写无部分生效，抛出时事务零写
        List<String> invalidCodes = distinctCodes.stream()
                .filter(code -> !idByCode.containsKey(code))
                .toList();
        if (!invalidCodes.isEmpty()) {
            log.warn("矩阵覆写被拒（载荷含未登记权限码）：roleCode={}，invalidCodes={}", roleCode, invalidCodes);
            throw new BizException(
                    SystemErrorCode.PERMISSION_CODE_INVALID, HttpStatus.BAD_REQUEST, "载荷含未登记权限码：" + invalidCodes);
        }
        // 既有绑定对装载 + diff：载荷 id 集（校验后全量命中映射）与既有 permission_id 集合差集
        Set<Long> existingIds = rolePermissionMapper
                .selectList(Wrappers.<RolePermissionEntity>lambdaQuery()
                        .eq(RolePermissionEntity::getRoleId, role.getId())
                        .select(RolePermissionEntity::getPermissionId))
                .stream()
                .map(RolePermissionEntity::getPermissionId)
                .collect(Collectors.toSet());
        Set<Long> payloadIds = distinctCodes.stream().map(idByCode::get).collect(Collectors.toSet());
        // 删除侧 = 既有−载荷：含绑定指向已删权限点的残行（不在载荷 id 集即入删除侧，覆写顺带清理）
        List<Long> toRemoveIds =
                existingIds.stream().filter(id -> !payloadIds.contains(id)).toList();
        // 插入侧 = 载荷−既有：保持载荷序（管理台提交序即落库序）
        List<RolePermissionEntity> toInsert = distinctCodes.stream()
                .map(idByCode::get)
                .filter(id -> !existingIds.contains(id))
                .map(id -> {
                    RolePermissionEntity binding = new RolePermissionEntity();
                    binding.setRoleId(role.getId());
                    binding.setPermissionId(id);
                    return binding;
                })
                .toList();
        if (toRemoveIds.isEmpty() && toInsert.isEmpty()) {
            // 幂等保存不空转：无变更零写零事件（不触发刷新广播/会话清理）
            log.debug("矩阵覆写无变化短路：roleCode={}，permCodeCount={}", roleCode, distinctCodes.size());
            return toVO(role, distinctCodes);
        }
        if (!toRemoveIds.isEmpty()) {
            // 逻辑删除：@TableLogic 由 delete(wrapper) 转 UPDATE deleted=1（deleted=0 范围条件自动携带）
            rolePermissionMapper.delete(Wrappers.<RolePermissionEntity>lambdaQuery()
                    .eq(RolePermissionEntity::getRoleId, role.getId())
                    .in(RolePermissionEntity::getPermissionId, toRemoveIds));
        }
        for (RolePermissionEntity binding : toInsert) {
            // id 由 ASSIGN_ID 雪花自动生成（与 V1121 种子固定 id 号段空间天然分离）
            rolePermissionMapper.insert(binding);
        }
        log.info("角色权限矩阵覆写完成：roleCode={}，removed={}，inserted={}", roleCode, toRemoveIds.size(), toInsert.size());
        // 事务上下文内发布应用事件（B.2-6 发布方必须在事务代理内；AFTER_COMMIT 消费归 Task 6 装配）
        eventPublisher.publishEvent(new PermissionMatrixChangedEvent(roleCode));
        return toVO(role, distinctCodes);
    }

    @Override
    @Transactional
    public RoleAdminVO updateStatus(String roleCode, String status) {
        // 校验链同覆写（SYS-1041/SYS-1043）+ status 值域（未知值经 fromCode 收口 SYS-1031，既有码复用）
        RoleEntity role = locateWritableRole(roleCode);
        RoleStatus next = RoleStatus.fromCode(status);
        // 仅状态列实质变更：updateById 非空字段策略下其余投影列回写原值（updated_at 由触发器维护）
        role.setStatus(next);
        roleMapper.updateById(role);
        log.info("角色启停完成：roleCode={}，status={}", roleCode, next.getCode());
        // 事务上下文内发布矩阵变更事件（停用角色存量会话摘要不回溯撤销、重登录失效——广播归 Task 6）
        eventPublisher.publishEvent(new PermissionMatrixChangedEvent(roleCode));
        return toVO(role, loadPermCodes(role.getId()));
    }

    /**
     * 写路径共用前置校验：角色码定位 + ADMIN 不可维护拒绝（两写端点同链，Task 5）。
     *
     * @param roleCode 角色编码，非空；来源：管理端路径参数
     * @return 定位到的角色实体（VO 组装五字段投影），非空
     * @throws BizException SYS-1041（角色不存在，404——误传/已删角色码）、SYS-1043（ADMIN
     *                      不可维护，400——运行期全放语义无绑定行可维护，D3 注记延伸）
     */
    private RoleEntity locateWritableRole(String roleCode) {
        // 按码定位（deleted=0 由 @TableLogic 携带；唯一码至多一行，selectList 形态与读链一致）
        List<RoleEntity> roles = roleMapper.selectList(Wrappers.<RoleEntity>lambdaQuery()
                .eq(RoleEntity::getRoleCode, roleCode)
                .select(
                        RoleEntity::getId,
                        RoleEntity::getRoleCode,
                        RoleEntity::getRoleName,
                        RoleEntity::getStatus,
                        RoleEntity::getDataScopeType));
        if (roles.isEmpty()) {
            log.warn("管理台写操作被拒（角色不存在）：roleCode={}", roleCode);
            throw new BizException(SystemErrorCode.ROLE_NOT_FOUND, HttpStatus.NOT_FOUND, "角色不存在：" + roleCode);
        }
        RoleEntity role = roles.get(0);
        if (SecurityConstants.ADMIN_ROLE_CODE.equals(role.getRoleCode())) {
            log.warn("管理台写操作被拒（内置超管不可维护）：roleCode={}", roleCode);
            throw new BizException(
                    SystemErrorCode.ROLE_ADMIN_IMMUTABLE, HttpStatus.BAD_REQUEST, "内置超管角色不可维护：" + roleCode);
        }
        return role;
    }

    /**
     * 实查单角色当前绑定码集（updateStatus 返回组装用，两步单表禁连表，Task 5）。
     *
     * @param roleId 角色 ID，非空；来源：写事务内定位的角色行
     * @return 绑定权限码清单（绑定序）；无绑定或绑定全部指向已删权限点时为空清单，非 null
     */
    private List<String> loadPermCodes(Long roleId) {
        List<RolePermissionEntity> bindings =
                rolePermissionMapper.selectList(Wrappers.<RolePermissionEntity>lambdaQuery()
                        .eq(RolePermissionEntity::getRoleId, roleId)
                        .select(RolePermissionEntity::getPermissionId));
        if (bindings.isEmpty()) {
            return List.of();
        }
        // 空集合会产非法 IN 语句，前置去重装载（读链同防线）；残行（指向已删权限点）映射 null 丢弃
        Map<Long, String> codeById =
                permissionMapper
                        .selectByIds(bindings.stream()
                                .map(RolePermissionEntity::getPermissionId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(PermissionEntity::getId, PermissionEntity::getPermCode));
        return bindings.stream()
                .map(binding -> codeById.get(binding.getPermissionId()))
                .filter(Objects::nonNull)
                .toList();
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
