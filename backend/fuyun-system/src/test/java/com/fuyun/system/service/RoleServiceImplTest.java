package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.entity.UserRoleEntity;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import com.fuyun.system.mapper.UserRoleMapper;
import com.fuyun.system.service.impl.RoleServiceImpl;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 角色服务单元测试（登录会话角色摘要与权限集展开查询，两步/四步单表查询语义）。
 *
 * <p>覆盖：findRoleCodesByUserId——有绑定且角色启用返回编码清单、无绑定空清单短路、
 * 查询条件携带"仅启用角色"过滤（停用角色不入会话的写回断言）；findPermissionCodesByUserId
 * （PR-4D 登录链路 permissions 填实）——普通用户四步单表链展开、无绑定空清单、ADMIN 特判
 * 全表导出不触绑定查询（D3 裁定）、停用角色不展开、角色码复查空集/权限绑定空集两防御短路。
 * mapper 以 Mockito 模拟。
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Captor
    private ArgumentCaptor<Wrapper<RoleEntity>> roleQueryCaptor;

    private RoleServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 两步/四步查询的 lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RoleEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserRoleEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PermissionEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RolePermissionEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RoleServiceImpl(userRoleMapper, permissionMapper, rolePermissionMapper);
        ReflectionTestUtils.setField(service, "baseMapper", roleMapper);
        ReflectionTestUtils.setField(service, "entityClass", RoleEntity.class);
    }

    @Test
    @DisplayName("用户有绑定且角色启用：返回角色编码清单，查询条件含仅启用过滤")
    void findRoleCodesReturnsBoundActiveRoleCodes() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding(1001L), binding(1002L)));
        RoleEntity admin = new RoleEntity();
        admin.setId(1001L);
        admin.setRoleCode("ADMIN");
        when(roleMapper.selectList(any())).thenReturn(List.of(admin));

        List<String> roleCodes = service.findRoleCodesByUserId(123L);

        assertThat(roleCodes).containsExactly("ADMIN");
        // 第二步查询条件绑定"仅启用角色"过滤（停用角色的权限语义失效，不入会话）
        verify(roleMapper).selectList(roleQueryCaptor.capture());
        LambdaQueryWrapper<RoleEntity> queryWrapper = asLambdaQueryWrapper(roleQueryCaptor.getValue());
        // MP 3.5.17 查询 wrapper 参数在片段渲染时回填：先取 SQL 片段再断言参数
        queryWrapper.getSqlSegment();
        assertThat(queryWrapper.getParamNameValuePairs().values()).contains(RoleStatus.ACTIVE);
    }

    @Test
    @DisplayName("用户无角色绑定：返回空清单且不触达角色表（短路查询）")
    void findRoleCodesReturnsEmptyWithoutBindings() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.findRoleCodesByUserId(123L)).isEmpty();
        verify(roleMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("普通用户权限展开：四步单表链返回绑定角色展开的 perm_code（绑定→角色→绑定关系→投影）")
    void findPermissionCodesExpandsBoundActiveRoles() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding(1001L)));
        // 同一 RoleEntity 双用：findRoleCodes 步骤取 roleCode，按码复查 id 步骤取 id（两次调用同桩）
        RoleEntity doctor = role(1001L, "DOCTOR");
        when(roleMapper.selectList(any())).thenReturn(List.of(doctor));
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(rolePermission(1001L, 2001L), rolePermission(1001L, 2002L)));
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(permission(2001L, "GET /api/v1/patients"), permission(2002L, "MENU /dashboard")));

        List<String> permCodes = service.findPermissionCodesByUserId(123L);

        assertThat(permCodes).containsExactlyInAnyOrder("GET /api/v1/patients", "MENU /dashboard");
        // 四步单表链全触达：绑定表一次+角色表两次（取码/复查 id）+绑定关系表一次+权限表一次（无连表无 N+1）
        verify(userRoleMapper).selectList(any());
        verify(roleMapper, times(2)).selectList(any());
        verify(rolePermissionMapper).selectList(any());
        verify(permissionMapper).selectList(any());
    }

    @Test
    @DisplayName("用户无角色绑定：权限展开返回空清单且不触达角色/绑定关系/权限表")
    void findPermissionCodesReturnsEmptyWithoutBindings() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.findPermissionCodesByUserId(123L)).isEmpty();
        verify(roleMapper, never()).selectList(any());
        verify(rolePermissionMapper, never()).selectList(any());
        verify(permissionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("ADMIN 特判全表导出：直接投影 sys_permission 全量 perm_code（含 MENU），不触绑定关系查询（D3 裁定）")
    void findPermissionCodesReturnsFullTableForAdminWithoutBindingQueries() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding(1001L)));
        when(roleMapper.selectList(any())).thenReturn(List.of(role(1001L, "ADMIN")));
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(
                        permission(2001L, "POST /api/v1/billing/refunds"), permission(2002L, "MENU /dashboard")));

        List<String> permCodes = service.findPermissionCodesByUserId(123L);

        // 全命名空间导出：API 与 MENU 一并返回（前端侧栏可见性依赖，D3 前端共源）
        assertThat(permCodes).containsExactlyInAnyOrder("POST /api/v1/billing/refunds", "MENU /dashboard");
        // ADMIN 短路：角色表仅触达一次（findRoleCodes 取码），不按码复查、不触绑定关系与逐点投影
        verify(roleMapper, times(1)).selectList(any());
        verify(rolePermissionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("停用角色不展开：ACTIVE 过滤后无启用角色码，权限返回空清单（复用 findRoleCodes 过滤语义）")
    void findPermissionCodesSkipsDisabledRoles() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding(1001L)));
        // 绑定角色停用：findRoleCodesByUserId 的 ACTIVE 过滤投影为空（停用角色的权限语义失效）
        when(roleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.findPermissionCodesByUserId(123L)).isEmpty();
        verify(rolePermissionMapper, never()).selectList(any());
        verify(permissionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("角色码复查空集防御：code→id 复查为空时短路返回，不触绑定关系与权限表")
    void findPermissionCodesReturnsEmptyWhenRoleCodeRequeryYieldsNothing() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding(1001L)));
        // 连续桩：第一次（取角色码）返回 DOCTOR，第二次（按码复查 id）返回空（会话期内角色停用的防御面）
        when(roleMapper.selectList(any()))
                .thenReturn(List.of(role(1001L, "DOCTOR")))
                .thenReturn(List.of());

        assertThat(service.findPermissionCodesByUserId(123L)).isEmpty();
        verify(rolePermissionMapper, never()).selectList(any());
        verify(permissionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("角色无权限绑定：绑定关系空集时短路返回空清单，不触权限表投影")
    void findPermissionCodesReturnsEmptyWhenRolesHaveNoPermissionBindings() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(binding(1001L)));
        when(roleMapper.selectList(any())).thenReturn(List.of(role(1001L, "DOCTOR")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.findPermissionCodesByUserId(123L)).isEmpty();
        verify(permissionMapper, never()).selectList(any());
    }

    /** 构造用户-角色绑定样本 */
    private UserRoleEntity binding(Long roleId) {
        UserRoleEntity binding = new UserRoleEntity();
        binding.setUserId(123L);
        binding.setRoleId(roleId);
        return binding;
    }

    /** 构造角色样本（id 与编码双承载：取码步骤与按码复查 id 步骤共用同一实体） */
    private RoleEntity role(Long roleId, String roleCode) {
        RoleEntity role = new RoleEntity();
        role.setId(roleId);
        role.setRoleCode(roleCode);
        return role;
    }

    /** 构造角色-权限绑定样本 */
    private RolePermissionEntity rolePermission(Long roleId, Long permissionId) {
        RolePermissionEntity binding = new RolePermissionEntity();
        binding.setRoleId(roleId);
        binding.setPermissionId(permissionId);
        return binding;
    }

    /** 构造权限点样本 */
    private PermissionEntity permission(Long permissionId, String permCode) {
        PermissionEntity permission = new PermissionEntity();
        permission.setId(permissionId);
        permission.setPermCode(permCode);
        return permission;
    }

    /** 将捕获的 Wrapper 断言为 LambdaQueryWrapper 以读取查询参数 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RoleEntity> asLambdaQueryWrapper(Wrapper<RoleEntity> wrapper) {
        return (LambdaQueryWrapper<RoleEntity>) wrapper;
    }
}
