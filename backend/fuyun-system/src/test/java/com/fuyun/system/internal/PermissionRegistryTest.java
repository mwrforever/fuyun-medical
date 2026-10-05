package com.fuyun.system.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.system.entity.PermissionEntity;
import com.fuyun.system.entity.RoleEntity;
import com.fuyun.system.entity.RolePermissionEntity;
import com.fuyun.system.enums.RoleStatus;
import com.fuyun.system.mapper.PermissionMapper;
import com.fuyun.system.mapper.RoleMapper;
import com.fuyun.system.mapper.RolePermissionMapper;
import java.util.List;
import java.util.Optional;
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
import org.slf4j.LoggerFactory;

/**
 * 权限点登记面单元测试（PR-4D W-37 主体构件，D1/D4 裁定的可执行化）。
 *
 * <p>覆盖：perm_code「动词+空格+路径模板」形态解析、PathPattern 模板对路径变量请求的归一命中、
 * 方法维度隔离（同路径 GET/POST 权限差异）、未登记路径 empty、停用角色绑定不入允许集
 * （与 findRoleCodesByUserId 的 ACTIVE 过滤语义一致）、load 幂等可重载（清空重建，供单测与
 * 未来 PR-4F 刷新通道复用）、非法权限点编码跳过、装载尾段同 method 面重叠模式互测 warn
 * 守护（评审 B-1 修复环，命中与不误报双面）。mapper 以 Mockito 模拟（三步单表查询
 * 不触库，TableInfo 手动初始化供 lambda 条件列名解析）。
 */
@ExtendWith(MockitoExtension.class)
class PermissionRegistryTest {

    /** 结算查询权限点：精确模板（无中间路径变量）直命中样本 */
    private static final String SETTLEMENT_CODE = "GET /api/v1/billing/settlements/{no}";

    /** 退款审批权限点：带中间路径变量段 {id} 的归一命中样本 */
    private static final String REFUND_APPROVE_CODE = "POST /api/v1/billing/refunds/{id}/approve";

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private RoleMapper roleMapper;

    @Captor
    private ArgumentCaptor<Wrapper<RoleEntity>> roleQueryCaptor;

    private PermissionRegistry registry;

    @BeforeAll
    static void initTableInfo() {
        // 三步单表查询的 lambda 条件列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PermissionEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), RolePermissionEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), RoleEntity.class);
    }

    @BeforeEach
    void setUp() {
        registry = new PermissionRegistry(permissionMapper, rolePermissionMapper, roleMapper);
        // 共享种子数据：两个 API 权限点（101 结算读/102 退款审批）+ 绑定（102 同时绑启用与停用角色）
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(perm(101L, SETTLEMENT_CODE), perm(102L, REFUND_APPROVE_CODE)));
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(bind(101L, 1L), bind(102L, 2L), bind(102L, 3L)));
        // mock 返回值即真实查询结果：ACTIVE 过滤在查询条件（见下方 wrapper 写回断言），
        // 停用 PHARMACIST 不会出现在该查询结果中
        when(roleMapper.selectList(any()))
                .thenReturn(List.of(role(1L, "CASHIER", RoleStatus.ACTIVE), role(2L, "DOCTOR", RoleStatus.ACTIVE)));
    }

    @Test
    @DisplayName("装载后精确路径直命中：resolve 返回条目携带 permCode 与允许角色集")
    void resolveHitsExactPathAfterLoad() {
        registry.load();

        Optional<PermissionRegistry.PermissionEntry> entry =
                registry.resolve("GET", "/api/v1/billing/settlements/{no}");

        assertThat(entry).isPresent();
        assertThat(entry.get().permCode()).isEqualTo(SETTLEMENT_CODE);
        assertThat(entry.get().allowedRoles()).containsExactly("CASHIER");
    }

    @Test
    @DisplayName("带路径变量请求归一命中：POST /refunds/123/approve 匹配模板 /refunds/{id}/approve，角色查询携带 ACTIVE 过滤")
    void resolveNormalizesPathVariables() {
        registry.load();

        Optional<PermissionRegistry.PermissionEntry> entry =
                registry.resolve("POST", "/api/v1/billing/refunds/123/approve");

        assertThat(entry).isPresent();
        assertThat(entry.get().permCode()).isEqualTo(REFUND_APPROVE_CODE);
        assertThat(entry.get().allowedRoles()).containsExactly("DOCTOR");
        // 停用角色不入允许集的机制面断言（照 RoleServiceImplTest 先例）：角色表查询条件
        // 携带"仅启用角色"过滤——wrapper 参数在片段渲染时回填，先取 SQL 片段再断言参数
        verify(roleMapper).selectList(roleQueryCaptor.capture());
        LambdaQueryWrapper<RoleEntity> roleQuery = asLambdaQueryWrapper(roleQueryCaptor.getValue());
        roleQuery.getSqlSegment();
        assertThat(roleQuery.getParamNameValuePairs().values()).contains(RoleStatus.ACTIVE);
    }

    @Test
    @DisplayName("方法不匹配返回 empty：同路径 GET 与 POST 权限互相隔离（D1 方法前缀粒度）")
    void resolveReturnsEmptyWhenMethodMismatches() {
        registry.load();

        assertThat(registry.resolve("GET", "/api/v1/billing/refunds/123/approve"))
                .isEmpty();
    }

    @Test
    @DisplayName("未登记路径返回 empty：矩阵外路径由拦截器按 D4 放行语义处置")
    void resolveReturnsEmptyForUnregisteredPath() {
        registry.load();

        assertThat(registry.resolve("GET", "/api/v1/system/dicts/gender")).isEmpty();
    }

    @Test
    @DisplayName("重复 load 幂等：清空重建后旧条目消失、新条目在位（供刷新通道复用）")
    void reloadRebuildsMatrixIdempotently() {
        registry.load();
        // 二次装载换数据源批次：仅一条新权限点，验证清空重建而非追加
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(perm(201L, "GET /api/v1/ward/infusion-board/{wardId}")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(bind(201L, 2L)));

        registry.load();

        assertThat(registry.resolve("GET", "/api/v1/billing/settlements/{no}"))
                .as("旧批次条目应随清空重建消失")
                .isEmpty();
        assertThat(registry.resolve("GET", "/api/v1/ward/infusion-board/W01")).isPresent();
    }

    @Test
    @DisplayName("非法权限点编码跳过：无空格分隔的 perm_code 不入矩阵并留 warn，其余条目正常装载")
    void loadSkipsMalformedPermCode() {
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(perm(101L, SETTLEMENT_CODE), perm(999L, "GET-/api/v1/broken")));

        registry.load();

        assertThat(registry.resolve("GET", "/-/api/v1/broken")).isEmpty();
        assertThat(registry.resolve("GET", "/api/v1/billing/settlements/{no}")).isPresent();
    }

    @Test
    @DisplayName("重叠路径模式装载留 warn 痕：字面量段与变量段模板互测命中即申报（评审 B-1 守护）")
    void loadWarnsOnOverlappingPatterns() {
        // 仓库真实重叠对同构样本：/patients/search（字面量尾段）与 /patients/{patientId}（变量尾段）
        when(permissionMapper.selectList(any()))
                .thenReturn(List.of(
                        perm(301L, "GET /api/v1/patient/patients/search"),
                        perm(302L, "GET /api/v1/patient/patients/{patientId}")));
        // 留一条绑定使三步查询链完整走通（roleMapper 消费 setUp 共享 stub，Mockito 严格模式零冗余）
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(bind(301L, 1L)));
        Logger registryLogger = (Logger) LoggerFactory.getLogger(PermissionRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
        try {
            registry.load();

            assertThat(appender.list)
                    .anyMatch(event -> Level.WARN.equals(event.getLevel())
                            && event.getFormattedMessage().contains("重叠路径模式")
                            && event.getFormattedMessage().contains("/patients/search")
                            && event.getFormattedMessage().contains("/patients/{patientId}"));
        } finally {
            registryLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("无重叠模式装载零告警：不同 method 面与互斥路径模板不触发重叠申报（守护不误报）")
    void loadStaysSilentWithoutOverlap() {
        // 既有共享种子（结算 GET + 退款审批 POST，方法面隔离）+ 同 method 面互斥模板，
        // 均不构成重叠——断言装载全程无重叠 warn
        Logger registryLogger = (Logger) LoggerFactory.getLogger(PermissionRegistry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        registryLogger.addAppender(appender);
        try {
            registry.load();

            assertThat(appender.list)
                    .noneMatch(event -> Level.WARN.equals(event.getLevel())
                            && event.getFormattedMessage().contains("重叠路径模式"));
        } finally {
            registryLogger.detachAppender(appender);
        }
    }

    /** 构造权限点行样本（仅装载用到的 id/permCode 字段）。 */
    private PermissionEntity perm(Long id, String permCode) {
        PermissionEntity entity = new PermissionEntity();
        entity.setId(id);
        entity.setPermCode(permCode);
        return entity;
    }

    /** 构造角色-权限绑定行样本。 */
    private RolePermissionEntity bind(Long permissionId, Long roleId) {
        RolePermissionEntity entity = new RolePermissionEntity();
        entity.setPermissionId(permissionId);
        entity.setRoleId(roleId);
        return entity;
    }

    /** 构造角色行样本（含启停状态：停用角色的绑定应被装载面过滤）。 */
    private RoleEntity role(Long id, String roleCode, RoleStatus status) {
        RoleEntity entity = new RoleEntity();
        entity.setId(id);
        entity.setRoleCode(roleCode);
        entity.setStatus(status);
        return entity;
    }

    /** 将捕获的 Wrapper 断言为 LambdaQueryWrapper 以读取查询参数（照 RoleServiceImplTest 先例）。 */
    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<RoleEntity> asLambdaQueryWrapper(Wrapper<RoleEntity> wrapper) {
        return (LambdaQueryWrapper<RoleEntity>) wrapper;
    }
}
