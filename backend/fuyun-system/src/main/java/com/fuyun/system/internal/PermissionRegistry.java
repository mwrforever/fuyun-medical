package com.fuyun.system.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.enums.PermissionType;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 权限点登记面（403 鉴权矩阵的进程内只读快照，PR-4D W-37 主体构件、D1/D4 裁定承载）。
 *
 * <p>核心职责：启动时从 sys_permission 装载全部 API 型权限点（perm_code 形态
 * {@code "METHOD /api/v1/..."}，动词+空格+路径模板）及其绑定的启用角色集，运行期以
 * {@link PathPatternParser}（与 MVC 路由匹配同源，{@code {var}} 模板段天然支持）按
 * 「HTTP 方法 → 路径模板 → 条目」两级索引解析请求归属——按方法分组后候选集为百级以内，
 * 单次解析微秒级。
 *
 * <p>装配与生命周期：Bean 注册归 SystemWebConfig @Bean 方法（构造器注入三 mapper），
 * 装配后显式调 {@link #load()} 一次；<b>权限数据变更须重启生效</b>，运行期动态刷新归
 * PR-4F（Redis pub/sub 通知各实例重载的演进注记）。{@link #load()} 幂等可重载：重复
 * 调用清空重建（构建新快照后整体替换引用，并发读始终看到一致版本），该语义供单测与
 * 未来刷新通道复用。
 *
 * <p>数据装载为三步单表查询（禁连表，照 RoleServiceImpl.findRoleCodesByUserId 先例）：
 * ①sys_permission 全量 API 行（perm_type='API'，deleted=0 由 @TableLogic 自动携带）；
 * ②sys_role_permission 按 permission_id IN 批量取绑定 role_id；③sys_role 按 id IN 取
 * ACTIVE 角色码（停用角色的绑定不入允许集，与会话角色摘要的 ACTIVE 过滤语义一致）。
 *
 * <p>线程安全：装载结果以 volatile 不可变引用整体替换（读侧无锁）；未装载时解析返回
 * empty（等价未登记面，由拦截器按 D4 放行语义处置）。
 */
@Slf4j
public class PermissionRegistry {

    /** 权限点条目：permCode 原文（METHOD + 模板）与允许角色编码集（空集=任何登录角色均拒，哨兵挂码语义） */
    public record PermissionEntry(String permCode, Set<String> allowedRoles) {}

    /** 路径模板解析器：defaultInstance 不可变可共享，与 MVC 路由匹配同源 */
    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /** 权限点数据访问：load 第一步（sys_permission API 行） */
    private final PermissionMapper permissionMapper;

    /** 角色-权限绑定数据访问：load 第二步（sys_role_permission 批量取 role_id） */
    private final RolePermissionMapper rolePermissionMapper;

    /** 角色数据访问：load 第三步（sys_role 取 ACTIVE 角色码） */
    private final RoleMapper roleMapper;

    /** 鉴权矩阵：HTTP 方法 →（路径模板 → 条目）；volatile 引用整体替换保证并发读一致 */
    private volatile Map<String, Map<PathPattern, PermissionEntry>> matrix = Map.of();

    /**
     * 全参构造器（装配归 SystemWebConfig @Bean 方法，构造器注入宪法 A.1-7）。
     *
     * @param permissionMapper     权限点 mapper，非空；来源：同模块 mapper 包（@MapperScan 注册）
     * @param rolePermissionMapper 绑定关系 mapper，非空；来源：同上
     * @param roleMapper           角色 mapper，非空；来源：同上
     */
    public PermissionRegistry(
            PermissionMapper permissionMapper, RolePermissionMapper rolePermissionMapper, RoleMapper roleMapper) {
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleMapper = roleMapper;
    }

    /**
     * 装载（重建）鉴权矩阵：三步单表查询后在内存组装，成功后整体替换快照引用。
     *
     * <p>执行流程：API 权限点行为空时直接落空矩阵（等价未登记面全放行，warn 面、
     * 完整性由 RbacMatrixIT 端点对照断言守护）；否则批量取绑定与启用角色码，
     * 按「第一个空格」切分 perm_code 的动词与路径模板（禁 split 正则歧义），
     * 非法编码（无空格/空段）跳过并记 warn，不阻断其余条目装载。
     *
     * <p>幂等语义：重复调用清空重建，旧条目不残留（测试与 PR-4F 刷新通道复用前提）。
     */
    public void load() {
        // 第一步：sys_permission 全量 API 行（精确投影 id/perm_code，A.4.3-14）
        List<PermissionEntity> permissions = permissionMapper.selectList(Wrappers.<PermissionEntity>lambdaQuery()
                .eq(PermissionEntity::getPermType, PermissionType.API)
                .select(PermissionEntity::getId, PermissionEntity::getPermCode));
        if (permissions.isEmpty()) {
            // 空权限面：落空矩阵（全放行+warn 由拦截器承担），不再触达绑定/角色表
            matrix = Map.of();
            log.warn("403鉴权矩阵装载为空：sys_permission 无 API 型权限点，全量请求将走未登记放行面");
            return;
        }
        List<Long> permissionIds =
                permissions.stream().map(PermissionEntity::getId).toList();
        // 第二步：绑定表按 permission_id IN 批量取 role_id（循环内禁 N+1，A.4.3-14）
        Map<Long, Set<Long>> permissionIdToRoleIds = rolePermissionMapper
                .selectList(Wrappers.<RolePermissionEntity>lambdaQuery()
                        .in(RolePermissionEntity::getPermissionId, permissionIds)
                        .select(RolePermissionEntity::getPermissionId, RolePermissionEntity::getRoleId))
                .stream()
                .collect(Collectors.groupingBy(
                        RolePermissionEntity::getPermissionId,
                        Collectors.mapping(RolePermissionEntity::getRoleId, Collectors.toSet())));
        // 第三步：角色表按 id IN 取启用角色编码投影（停用角色的绑定不入允许集——ACTIVE 过滤语义）
        Set<Long> boundRoleIds =
                permissionIdToRoleIds.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Map<Long, String> roleIdToCode = boundRoleIds.isEmpty()
                ? Map.of()
                : roleMapper
                        .selectList(Wrappers.<RoleEntity>lambdaQuery()
                                .in(RoleEntity::getId, boundRoleIds)
                                .eq(RoleEntity::getStatus, RoleStatus.ACTIVE)
                                .select(RoleEntity::getId, RoleEntity::getRoleCode))
                        .stream()
                        .collect(Collectors.toMap(
                                RoleEntity::getId, RoleEntity::getRoleCode, (existing, replacement) -> existing));
        // 组装新快照：外键 HTTP 方法（大写归一），内层路径模板 → 条目
        Map<String, Map<PathPattern, PermissionEntry>> fresh = new HashMap<>();
        int skipped = 0;
        for (PermissionEntity permission : permissions) {
            String permCode = permission.getPermCode();
            // 按第一个空格切分动词与路径模板（禁 split 正则歧义，纪律 3）
            int separatorIndex = permCode.indexOf(' ');
            if (separatorIndex <= 0 || separatorIndex == permCode.length() - 1) {
                // 脏数据防御：无空格或空段编码不入矩阵，warn 留痕不阻断装载
                skipped++;
                log.warn("403鉴权矩阵跳过非法权限点编码：permCode={}", permCode);
                continue;
            }
            String method = permCode.substring(0, separatorIndex).toUpperCase(Locale.ROOT);
            String pathTemplate = permCode.substring(separatorIndex + 1);
            // 绑定角色经 ACTIVE 投影映射为编码集；停用角色在 roleIdToCode 无映射即被过滤
            Set<String> allowedRoles = permissionIdToRoleIds.getOrDefault(permission.getId(), Set.of()).stream()
                    .map(roleIdToCode::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            PermissionEntry entry = new PermissionEntry(permCode, Set.copyOf(allowedRoles));
            fresh.computeIfAbsent(method, key -> new HashMap<>()).put(PARSER.parse(pathTemplate), entry);
        }
        // 整体替换快照引用：并发读要么全旧要么全新，不清空旧 Map（幂等重建的原子性锚点）
        matrix = fresh;
        log.info("403鉴权矩阵装载完成：API权限点={}条，跳过非法编码={}条", permissions.size() - skipped, skipped);
    }

    /**
     * 解析请求归属的权限点条目。
     *
     * @param method HTTP 方法，非空；大小写不敏感（容器返回大写，归一防御）
     * @param path   请求路径，非空；含路径变量实参段（如 /refunds/123/approve）
     * @return 命中的权限点条目（permCode + 允许角色集）；方法或路径未登记时为 empty（拦截器按 D4 放行+warn 处置）
     */
    public Optional<PermissionEntry> resolve(String method, String path) {
        Map<PathPattern, PermissionEntry> candidates = matrix.get(method.toUpperCase(Locale.ROOT));
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        PathContainer requestPath = PathContainer.parsePath(path);
        for (Map.Entry<PathPattern, PermissionEntry> candidate : candidates.entrySet()) {
            if (candidate.getKey().matches(requestPath)) {
                return Optional.of(candidate.getValue());
            }
        }
        return Optional.empty();
    }
}
