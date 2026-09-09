package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.UserRoleEntity;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.RoleMapper;
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
 * 角色服务单元测试（登录会话角色摘要查询，两步单表查询语义）。
 *
 * <p>覆盖：有绑定且角色启用 → 编码清单返回；无绑定 → 空清单且不触达角色表（短路）；
 * 查询条件携带"仅启用角色"过滤（停用角色不入会话的写回断言）。mapper 以 Mockito 模拟。
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    @Captor
    private ArgumentCaptor<Wrapper<RoleEntity>> roleQueryCaptor;

    private RoleServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        // 两步查询的 lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RoleEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserRoleEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new RoleServiceImpl(userRoleMapper);
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

    /** 构造用户-角色绑定样本 */
    private UserRoleEntity binding(Long roleId) {
        UserRoleEntity binding = new UserRoleEntity();
        binding.setUserId(123L);
        binding.setRoleId(roleId);
        return binding;
    }

    /** 将捕获的 Wrapper 断言为 LambdaQueryWrapper 以读取查询参数 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RoleEntity> asLambdaQueryWrapper(Wrapper<RoleEntity> wrapper) {
        return (LambdaQueryWrapper<RoleEntity>) wrapper;
    }
}
