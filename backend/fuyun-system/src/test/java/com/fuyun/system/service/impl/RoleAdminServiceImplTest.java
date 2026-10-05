package com.fuyun.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.enums.DataScopeType;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.internal.PermissionMatrixChangedEvent;
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
import org.springframework.context.ApplicationEventPublisher;

/**
 * 角色管理服务单元测试（权限管理台读链 + 矩阵覆写/角色启停写链，PR-4F Task 4/5）。
 *
 * <p>读链覆盖 listRoles 三步单表链语义：①全量角色（ADMIN 无绑定行→空清单，运行期全放语义
 * 由前端特殊渲染，D3 注记；NURSE 绑定行→API/ELEMENT 混出展开码集，F2 形态 A）；
 * ②无任何角色返回空清单；③有角色但无绑定行 permCodes=空清单非 null；④逻辑删除过滤
 * 由 mapper 层 @TableLogic 承担，service 直查不重复拼 deleted 条件；⑤残行过滤——绑定
 * 指向已删权限点的行在码集组装中丢弃（Task 4 审查遗留 Minor ①收口）。
 *
 * <p>写链覆盖（Task 5，核心包 100% 约束）：矩阵覆写 diff 删插+事件、SYS-1041/1042/1043
 * 三拒绝码、无变化零空转、载荷去重、空载荷清空全部绑定；启停合法值落库+事件、非法值
 * SYS-1031（既有码复用）、GHOST/ADMIN 拒绝。mapper 与 ApplicationEventPublisher 以
 * Mockito 模拟。
 */
@ExtendWith(MockitoExtension.class)
class RoleAdminServiceImplTest {

    /** 角色样本 id 分配表：roleOf 按编码取 id，与 bind 构造的 roleId 锚定一致 */
    private static final Map<String, Long> ROLE_IDS = Map.of("ADMIN", 9001L, "NURSE", 9002L, "DOCTOR", 9003L);

    /** NURSE 角色样本 id：绑定行构造与分组展开断言共用 */
    private static final Long roleIdOfNurse = ROLE_IDS.get("NURSE");

    /** 矩阵覆写样本权限点 id 分配表：A/B/C 三码与既有/载荷差集断言锚定一致 */
    private static final Long idA = 101L;

    private static final Long idB = 102L;
    private static final Long idC = 103L;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        service = new RoleAdminServiceImpl(roleMapper, rolePermissionMapper, permissionMapper, eventPublisher);
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

    @Test
    @DisplayName("残行过滤：绑定指向已删权限点的行被丢弃，permCodes 仅含存活权限码（Task 4 审查遗留收口）")
    void listRolesDropsBindingsPointingAtDeletedPermissions() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        // 两条绑定其一指向已逻辑删除的权限点（99 无存活 perm 行，selectByIds 不返回）
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(bind(roleIdOfNurse, 11L), bind(roleIdOfNurse, 99L)));
        when(permissionMapper.selectByIds(any())).thenReturn(List.of(perm(11L, "nursing:ward:btn:task")));

        List<RoleAdminVO> result = service.listRoles();

        // 残行（permission_id=99）映射 null 被 nonNull 过滤，码集无 null 位且仅含存活码
        assertThat(result.get(0).permCodes()).containsExactly("nursing:ward:btn:task");
    }

    // ---------------------------------------------------------------- 写链：矩阵全量覆写（Task 5）

    @Test
    @DisplayName("矩阵覆写 diff 删插：既有 {A,B} 载荷 {B,C} → 删 A（逻辑删除）+ 插 C（id 留空 ASSIGN_ID）+ 发事件")
    void overwritePermissionsInsertsAddedAndLogicallyDeletesRemoved() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(bind(roleIdOfNurse, idA), bind(roleIdOfNurse, idB)));
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm(idB, "B"), perm(idC, "C"), perm(idA, "A")));

        service.overwritePermissions("NURSE", List.of("B", "C"));

        // 删 A：逻辑删除（@TableLogic 由 delete(wrapper) 转 UPDATE deleted=1，无物理 DELETE）
        verify(rolePermissionMapper).delete(any());
        // 插 C：仅赋 roleId/permissionId，id 留空由 ASSIGN_ID 雪花生成（与 V1121 种子固定号段天然分离）
        ArgumentCaptor<RolePermissionEntity> insertCaptor = ArgumentCaptor.forClass(RolePermissionEntity.class);
        verify(rolePermissionMapper).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getRoleId()).isEqualTo(roleIdOfNurse);
        assertThat(insertCaptor.getValue().getPermissionId()).isEqualTo(idC);
        assertThat(insertCaptor.getValue().getId()).isNull();
        // B 保留（既删侧无 idB、插侧仅 idC，times(1) 默认语义已锁单次删单次插）
        verify(eventPublisher).publishEvent(new PermissionMatrixChangedEvent("NURSE"));
    }

    @Test
    @DisplayName("矩阵覆写拒绝未知角色：SYS-1041（404）")
    void overwritePermissionsRejectsUnknownRoleWithSys1041() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.overwritePermissions("GHOST", List.of()))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.ROLE_NOT_FOUND));
    }

    @Test
    @DisplayName("矩阵覆写拒绝 ADMIN：SYS-1043（运行期全放语义无绑定行可维护，D3）")
    void overwritePermissionsRejectsAdminWithSys1043() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("ADMIN")));

        assertThatThrownBy(() -> service.overwritePermissions("ADMIN", List.of()))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.ROLE_ADMIN_IMMUTABLE));
    }

    @Test
    @DisplayName("矩阵覆写拒绝未登记码：SYS-1042 detail 含全部非法码，且事务零写零事件（无部分生效）")
    void overwritePermissionsRejectsUnregisteredCodesWithSys1042AndZeroWrites() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        // 载荷 {A,X,Y} 仅 A 已登记（X/Y 未登记）
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm(idA, "A")));

        assertThatThrownBy(() -> service.overwritePermissions("NURSE", List.of("A", "X", "Y")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.PERMISSION_CODE_INVALID);
                    // detail 列全部非法码（前端据此整体修正重发）
                    assertThat(ex.getMessage()).contains("X").contains("Y");
                });
        // 校验先于 diff：零绑定读、零写、零事件
        verify(rolePermissionMapper, never()).selectList(any());
        verify(rolePermissionMapper, never()).delete(any());
        verify(rolePermissionMapper, never()).insert(any(RolePermissionEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("无变化载荷零空转：既有 {A,B} 载荷 {B,A} 乱序同集 → 零删零插零事件（幂等保存）")
    void overwritePermissionsSkipsWriteAndEventWhenPayloadUnchanged() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(bind(roleIdOfNurse, idA), bind(roleIdOfNurse, idB)));
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm(idA, "A"), perm(idB, "B")));

        RoleAdminVO result = service.overwritePermissions("NURSE", List.of("B", "A"));

        verify(rolePermissionMapper, never()).delete(any());
        verify(rolePermissionMapper, never()).insert(any(RolePermissionEntity.class));
        // 无变更不触发刷新广播/会话清理
        verify(eventPublisher, never()).publishEvent(any());
        // 返回即目标态：permCodes=载荷序（保序去重后的落库终态）
        assertThat(result.permCodes()).containsExactly("B", "A");
    }

    @Test
    @DisplayName("载荷去重：{A,A,B} 按 {A,B} 处理——仅插缺的 B 一次，A 既有保留零删")
    void overwritePermissionsDeduplicatesPayloadCodes() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(bind(roleIdOfNurse, idA)));
        when(permissionMapper.selectList(any())).thenReturn(List.of(perm(idA, "A"), perm(idB, "B")));

        service.overwritePermissions("NURSE", List.of("A", "A", "B"));

        // 去重后 toAdd={B}：插一次且仅 B；无删除侧
        ArgumentCaptor<RolePermissionEntity> insertCaptor = ArgumentCaptor.forClass(RolePermissionEntity.class);
        verify(rolePermissionMapper, times(1)).insert(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getPermissionId()).isEqualTo(idB);
        verify(rolePermissionMapper, never()).delete(any());
        verify(eventPublisher).publishEvent(new PermissionMatrixChangedEvent("NURSE"));
    }

    @Test
    @DisplayName("空载荷清空全部绑定：删既有行并发事件（PUT 全量覆写语义），跳过权限码登记查询")
    void overwritePermissionsClearsAllBindingsWhenPayloadEmpty() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(bind(roleIdOfNurse, idA)));

        service.overwritePermissions("NURSE", List.of());

        // 空载荷不触权限表（空 IN 为非法语句防线）；既有绑定全量入删除侧；清空属变更发事件
        verify(permissionMapper, never()).selectList(any());
        verify(rolePermissionMapper).delete(any());
        verify(rolePermissionMapper, never()).insert(any(RolePermissionEntity.class));
        verify(eventPublisher).publishEvent(new PermissionMatrixChangedEvent("NURSE"));
    }

    // ---------------------------------------------------------------- 写链：角色启停（Task 5）

    @Test
    @DisplayName("角色启停合法值：status 落库 + 发事件，VO 回组装当前绑定码集")
    void updateStatusTransitionsAndPublishesEvent() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(bind(roleIdOfNurse, 11L)));
        when(permissionMapper.selectByIds(any())).thenReturn(List.of(perm(11L, "nursing:ward:btn:task")));

        RoleAdminVO result = service.updateStatus("NURSE", "DISABLED");

        ArgumentCaptor<RoleEntity> updateCaptor = ArgumentCaptor.forClass(RoleEntity.class);
        verify(roleMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(RoleStatus.DISABLED);
        verify(eventPublisher).publishEvent(new PermissionMatrixChangedEvent("NURSE"));
        // 返回终态：status=落库值，permCodes=当前绑定码集实查（停用不清绑定，复启用即恢复）
        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.permCodes()).containsExactly("nursing:ward:btn:task");
    }

    @Test
    @DisplayName("角色启停非法值：SYS-1031（ENUM_VALUE_INVALID 既有码复用），零写零事件")
    void updateStatusRejectsInvalidValueWithSys1031() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));

        assertThatThrownBy(() -> service.updateStatus("NURSE", "FROZEN"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.ENUM_VALUE_INVALID));
        verify(roleMapper, never()).updateById(any(RoleEntity.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("角色启停拒绝未知角色：SYS-1041")
    void updateStatusRejectsUnknownRoleWithSys1041() {
        when(roleMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.updateStatus("GHOST", "ACTIVE"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.ROLE_NOT_FOUND));
    }

    @Test
    @DisplayName("角色启停拒绝 ADMIN：SYS-1043（内置超管不可启停）")
    void updateStatusRejectsAdminWithSys1043() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("ADMIN")));

        assertThatThrownBy(() -> service.updateStatus("ADMIN", "DISABLED"))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getErrorCode())
                        .isEqualTo(SystemErrorCode.ROLE_ADMIN_IMMUTABLE));
    }

    @Test
    @DisplayName("无绑定角色启停：permCodes 空清单非 null（VO 组装短路，不触权限表）")
    void updateStatusReturnsEmptyPermCodesWhenNoBindings() {
        when(roleMapper.selectList(any())).thenReturn(List.of(roleOf("NURSE")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        RoleAdminVO result = service.updateStatus("NURSE", "DISABLED");

        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.permCodes()).isNotNull().isEmpty();
        verify(permissionMapper, never()).selectByIds(any());
        verify(eventPublisher).publishEvent(new PermissionMatrixChangedEvent("NURSE"));
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
