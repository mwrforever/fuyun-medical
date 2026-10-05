package com.fuyun.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.enums.DataScopeType;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import com.fuyun.system.vo.RoleAdminVO;
import java.util.List;
import java.util.Map;
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

/**
 * 角色管理读服务单元测试（权限管理台角色清单与绑定码集展开，PR-4F Task 4）。
 *
 * <p>覆盖 listRoles 三步单表链语义：①全量角色（ADMIN 无绑定行→空清单，运行期全放语义
 * 由前端特殊渲染，D3 注记；NURSE 绑定行→API/ELEMENT 混出展开码集，F2 形态 A）；
 * ②无任何角色返回空清单；③有角色但无绑定行 permCodes=空清单非 null；④逻辑删除过滤
 * 由 mapper 层 @TableLogic 承担，service 直查不重复拼 deleted 条件。mapper 以 Mockito 模拟。
 */
@ExtendWith(MockitoExtension.class)
class RoleAdminServiceImplTest {

    /** 角色样本 id 分配表：roleOf 按编码取 id，与 bind 构造的 roleId 锚定一致 */
    private static final Map<String, Long> ROLE_IDS = Map.of("ADMIN", 9001L, "NURSE", 9002L, "DOCTOR", 9003L);

    /** NURSE 角色样本 id：绑定行构造与分组展开断言共用 */
    private static final Long roleIdOfNurse = ROLE_IDS.get("NURSE");

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Captor
    private ArgumentCaptor<Wrapper<RoleEntity>> roleQueryCaptor;

    @Captor
    private ArgumentCaptor<Wrapper<RolePermissionEntity>> bindingQueryCaptor;

    private RoleAdminServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 三步单表链的 lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RoleEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RolePermissionEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PermissionEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RoleAdminServiceImpl(roleMapper, rolePermissionMapper, permissionMapper);
    }

    @Test
    @DisplayName("正常路径：两角色返回绑定码集——ADMIN 无绑定行空清单（D3 运行期全放语义），NURSE 展开 API/ELEMENT 混出码集")
    void listRolesReturnsAllRolesWithBoundPermCodes() {
        // 正常路径：两角色（ADMIN 无绑定行→空清单；NURSE 两绑定行→展开码集）
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("ADMIN"), roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(bind(roleIdOfNurse, 11L), bind(roleIdOfNurse, 12L)));
        when(permissionMapper.selectByIds(any()))
                .thenReturn(List.of(perm(11L, "nursing:ward:btn:task"), perm(12L, "GET /api/v1/nursing/tasks")));

        List<RoleAdminVO> result = service.listRoles();

        assertThat(result).extracting(RoleAdminVO::roleCode).containsExactly("ADMIN", "NURSE");
        assertThat(result.get(0).permCodes()).isEmpty(); // ADMIN 无绑定行=空清单（运行期全放语义）
        assertThat(result.get(1).permCodes())
                .containsExactlyInAnyOrder("nursing:ward:btn:task", "GET /api/v1/nursing/tasks");
    }

    @Test
    @DisplayName("无任何角色：返回空清单且不触达绑定关系与权限表（短路查询）")
    void listRolesReturnsEmptyWhenNoRolesExist() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.listRoles()).isEmpty();
        verify(rolePermissionMapper, never()).selectList(any());
        verify(permissionMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("角色无绑定行：permCodes 为空清单非 null，不触达权限表批量投影")
    void listRolesReturnsEmptyPermCodesWhenNoBindings() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("DOCTOR")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        List<RoleAdminVO> result = service.listRoles();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).roleCode()).isEqualTo("DOCTOR");
        assertThat(result.get(0).permCodes()).isNotNull().isEmpty();
        verify(permissionMapper, never()).selectByIds(any());
    }

    @Test
    @DisplayName("逻辑删除行不重复过滤：查询链不额外拼 deleted 条件（@TableLogic 由 mapper 层自动携带）")
    void listRolesDoesNotAppendLogicDeleteCondition() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(bind(roleIdOfNurse, 11L)));
        when(permissionMapper.selectByIds(any())).thenReturn(List.of(perm(11L, "nursing:ward:btn:task")));

        service.listRoles();

        // 捕获两步查询 wrapper：SQL 片段不得含 deleted 条件（mapper 层 @TableLogic 已携带，service 重复拼则冗余）
        verify(roleMapper).selectList(roleQueryCaptor.capture());
        verify(rolePermissionMapper).selectList(bindingQueryCaptor.capture());
        assertThat(asLambdaQueryWrapper(roleQueryCaptor.getValue()).getSqlSegment())
                .doesNotContain("deleted");
        assertThat(asLambdaQueryWrapper(bindingQueryCaptor.getValue()).getSqlSegment())
                .doesNotContain("deleted");
    }

    /** 构造角色样本（id 按编码查分配表，与 bind 构造的 roleId 锚定一致；全字段承载 VO 组装） */
    private RoleEntity roleOf(String roleCode) {
        RoleEntity role = new RoleEntity();
        role.setId(ROLE_IDS.get(roleCode));
        role.setRoleCode(roleCode);
        role.setRoleName(roleCode + "角色");
        role.setStatus(RoleStatus.ACTIVE);
        role.setDataScopeType(DataScopeType.ALL);
        return role;
    }

    /** 构造角色-权限绑定样本 */
    private RolePermissionEntity bind(Long roleId, Long permissionId) {
        RolePermissionEntity binding = new RolePermissionEntity();
        binding.setRoleId(roleId);
        binding.setPermissionId(permissionId);
        return binding;
    }

    /** 构造权限点样本 */
    private PermissionEntity perm(Long permissionId, String permCode) {
        PermissionEntity permission = new PermissionEntity();
        permission.setId(permissionId);
        permission.setPermCode(permCode);
        return permission;
    }

    /** 将捕获的 Wrapper 断言为 LambdaQueryWrapper 以读取 SQL 片段 */
    @SuppressWarnings("unchecked")
    private <T> LambdaQueryWrapper<T> asLambdaQueryWrapper(Wrapper<T> wrapper) {
        return (LambdaQueryWrapper<T>) wrapper;
    }
}
