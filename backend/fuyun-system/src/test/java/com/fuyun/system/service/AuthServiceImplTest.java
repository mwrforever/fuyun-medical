package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.convert.AuthConverter;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.entity.EmployeeEntity;
import com.fuyun.system.entity.UserEntity;
import com.fuyun.system.enums.UserStatus;
import com.fuyun.system.mapper.EmployeeMapper;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.record.RefreshedAccess;
import com.fuyun.system.record.SessionData;
import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenPair;
import com.fuyun.system.service.impl.AuthServiceImpl;
import com.fuyun.system.vo.BigscreenTokenVO;
import com.fuyun.system.vo.LoginResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证应用服务单元测试（登录状态机与防枚举红线，BRIEF-PR3-01 §1.3 全分支）。
 *
 * <p>覆盖：成功登录（成功复位 + 双令牌签发 + 响应组装）、账号不存在/口令错误同文案 SYS-1001
 * （防枚举）、锁定期间拒绝且不比对口令（SYS-1002 文案含解锁时刻）、锁定已到期恢复、停用拒绝
 * （SYS-1006/403）、无员工行账号的会话身份兜底、refresh 换发（原 refresh 值回填）、logout 委派；
 * PR-4D 追加 permissions 填实断言（login 角色展开写入会话并透传出参、refresh 会话透传与旧会话
 * null 归一、哨兵签发零权限面）。依赖以 Mockito 模拟；AuthConverter 用 MapStruct 生成实现
 * （转换逻辑一并断言）。端到端 HTTP 链路归 B3.3 集成测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    /** 测试登录名：V303 种子同款（联调账号语义） */
    private static final String LOGIN_NAME = "admin";

    /** 测试用 bcrypt 口令哈希占位：仅承载"已存哈希"语义，真实性由 PasswordEncoder mock 决定 */
    private static final String PASSWORD_HASH = "$2a$10$unitTestHashPlaceholderValue00000000000000000000000000";

    /** 测试令牌值占位：令牌线格式语义归 TokenServiceImplTest，本类仅断言传递一致性 */
    private static final String ACCESS_TOKEN = "unit-access-token";

    private static final String REFRESH_TOKEN = "unit-refresh-token";

    /** 权限点固定清单：findPermissionCodesByUserId 的 mock 返回值（permissions 透传断言锚点，PR-4D） */
    private static final List<String> PERMISSION_CODES = List.of("GET /api/v1/patients", "MENU /dashboard");

    @Mock
    private IUserService userService;

    @Mock
    private IRoleService roleService;

    @Mock
    private EmployeeMapper employeeMapper;

    @Mock
    private ITokenService tokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Captor
    private ArgumentCaptor<SessionUser> sessionUserCaptor;

    /** 大屏订阅令牌 TTL 捕获器（短期凭证策略断言） */
    @Captor
    private ArgumentCaptor<Duration> ttlCaptor;

    private AuthServiceImpl authService;

    @BeforeAll
    static void initTableInfo() {
        // 员工反查 lambda 条件的列名解析依赖 TableInfo（容器外单测需手动初始化一次）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EmployeeEntity.class);
    }

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userService,
                roleService,
                employeeMapper,
                tokenService,
                passwordEncoder,
                AuthConverter.INSTANCE,
                new SecurityProperties(
                        "unit-test-only-hmac-secret-0123456789abcdef", Duration.ofHours(2), Duration.ofHours(24)));
    }

    @Test
    @DisplayName("成功登录：状态机复位 + 双令牌签发 + 响应组装（身份/角色/权限/有效期逐项断言）")
    void loginSucceedsAndIssuesTokenPairWithSessionReset() {
        UserEntity user = activeUser();
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);
        when(employeeMapper.selectOne(any())).thenReturn(employee(456L, "系统管理员", null));
        when(roleService.findRoleCodesByUserId(123L)).thenReturn(List.of("ADMIN"));
        when(roleService.findPermissionCodesByUserId(123L)).thenReturn(PERMISSION_CODES);
        when(passwordEncoder.matches("Fuyun@2026", PASSWORD_HASH)).thenReturn(true);
        when(tokenService.issue(any(SessionUser.class))).thenReturn(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));

        LoginResponse response = authService.login(new LoginRequest(LOGIN_NAME, "Fuyun@2026"));

        assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(Duration.ofHours(2).toSeconds());
        assertThat(response.user().userId()).isEqualTo(123L);
        assertThat(response.user().loginName()).isEqualTo(LOGIN_NAME);
        assertThat(response.user().displayName()).isEqualTo("系统管理员");
        assertThat(response.user().orgId()).isNull();
        assertThat(response.user().roles()).containsExactly("ADMIN");
        // permissions 契约锚定：角色展开的授权点集透传出参（PR-4D 填实，空集=无任何权限）
        assertThat(response.user().permissions()).containsExactlyElementsOf(PERMISSION_CODES);

        // 成功路径复位状态机（失败计数清零/锁定清空/最近登录时刻），签发入参为组装后的会话身份
        verify(userService).recordLoginSuccess(user);
        verify(userService, never()).recordLoginFailure(any());
        verify(tokenService).issue(sessionUserCaptor.capture());
        SessionUser issued = sessionUserCaptor.getValue();
        assertThat(issued.userId()).isEqualTo(123L);
        assertThat(issued.displayName()).isEqualTo("系统管理员");
        assertThat(issued.employeeId()).isEqualTo(456L);
        assertThat(issued.roles()).containsExactly("ADMIN");
        assertThat(issued.permissions()).containsExactlyElementsOf(PERMISSION_CODES);
    }

    @Test
    @DisplayName("账号不存在：按 SYS-1001/401 拒绝且与口令错误同文案（防用户枚举），不触达口令比对")
    void loginRejectsUnknownLoginNameWithAntiEnumerationMessage() {
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(null);

        assertThatThrownBy(() -> authService.login(new LoginRequest(LOGIN_NAME, "any-password")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.LOGIN_NAME_OR_PASSWORD_WRONG);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessage()).isEqualTo("登录名或密码错误");
                });
        verify(passwordEncoder, never()).matches(any(), any());
        verify(tokenService, never()).issue(any());
    }

    @Test
    @DisplayName("口令错误：走失败计数状态机后按 SYS-1001 拒绝，不签发令牌不复位状态机")
    void loginRejectsWrongPasswordAndRecordsFailure() {
        UserEntity user = activeUser();
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);
        when(passwordEncoder.matches("wrong", PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(LOGIN_NAME, "wrong")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.LOGIN_NAME_OR_PASSWORD_WRONG);
                    assertThat(ex.getMessage()).isEqualTo("登录名或密码错误");
                });
        verify(userService).recordLoginFailure(user);
        verify(userService, never()).recordLoginSuccess(any());
        verify(tokenService, never()).issue(any());
    }

    @Test
    @DisplayName("锁定期间拒绝：SYS-1002/401 且文案含解锁时刻，先于口令比对（不再累加失败计数）")
    void loginRejectsLockedAccountWithUnlockTimeBeforePasswordCheck() {
        UserEntity user = activeUser();
        user.setLockedUntil(OffsetDateTime.now().plusMinutes(10));
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(LOGIN_NAME, "Fuyun@2026")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.ACCOUNT_LOCKED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessage())
                            .contains("锁定")
                            .contains(user.getLockedUntil().toString());
                });
        verify(passwordEncoder, never()).matches(any(), any());
        verify(userService, never()).recordLoginFailure(any());
    }

    @Test
    @DisplayName("锁定已到期自动恢复：locked_until 为过去时刻时放行后续校验（登录成功）")
    void loginAllowsAccountWithExpiredLock() {
        UserEntity user = activeUser();
        user.setLockedUntil(OffsetDateTime.now().minusMinutes(1));
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);
        when(employeeMapper.selectOne(any())).thenReturn(employee(456L, "系统管理员", null));
        when(roleService.findRoleCodesByUserId(123L)).thenReturn(List.of("ADMIN"));
        when(roleService.findPermissionCodesByUserId(123L)).thenReturn(PERMISSION_CODES);
        when(passwordEncoder.matches("Fuyun@2026", PASSWORD_HASH)).thenReturn(true);
        when(tokenService.issue(any(SessionUser.class))).thenReturn(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));

        assertThat(authService
                        .login(new LoginRequest(LOGIN_NAME, "Fuyun@2026"))
                        .user()
                        .userId())
                .isEqualTo(123L);
        verify(userService).recordLoginSuccess(user);
    }

    @Test
    @DisplayName("停用账号拒绝：SYS-1006/403，不触达口令比对与状态机")
    void loginRejectsDisabledAccount() {
        UserEntity user = activeUser();
        user.setStatus(UserStatus.DISABLED);
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(LOGIN_NAME, "Fuyun@2026")))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(SystemErrorCode.ACCOUNT_DISABLED);
                    assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        verify(passwordEncoder, never()).matches(any(), any());
        verify(userService, never()).recordLoginFailure(any());
    }

    @Test
    @DisplayName("无员工行账号登录：会话身份以登录名兜底显示名，eid/orgId 为空（系统/接口账号）")
    void loginBuildsSessionUserWithoutEmployeeRow() {
        UserEntity user = activeUser();
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);
        when(employeeMapper.selectOne(any())).thenReturn(null);
        when(roleService.findRoleCodesByUserId(123L)).thenReturn(List.of());
        when(roleService.findPermissionCodesByUserId(123L)).thenReturn(List.of());
        when(passwordEncoder.matches("Fuyun@2026", PASSWORD_HASH)).thenReturn(true);
        when(tokenService.issue(any(SessionUser.class))).thenReturn(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));

        LoginResponse response = authService.login(new LoginRequest(LOGIN_NAME, "Fuyun@2026"));

        assertThat(response.user().displayName()).isEqualTo(LOGIN_NAME);
        assertThat(response.user().roles()).isEmpty();
        verify(tokenService).issue(sessionUserCaptor.capture());
        assertThat(sessionUserCaptor.getValue().employeeId()).isNull();
        assertThat(sessionUserCaptor.getValue().orgId()).isNull();
        assertThat(sessionUserCaptor.getValue().permissions()).isEmpty();
    }

    @Test
    @DisplayName("刷新换发：新 access + 原 refresh 值回填（P0 不轮换）+ 会话身份还原")
    void refreshReturnsMintedAccessWithOriginalRefreshToken() {
        SessionData session = new SessionData(
                123L, LOGIN_NAME, "系统管理员", 456L, null, List.of("ADMIN"), null, List.of("MENU /dashboard"));
        when(tokenService.refreshAccessToken(REFRESH_TOKEN)).thenReturn(new RefreshedAccess(ACCESS_TOKEN, session));

        LoginResponse response = authService.refresh(new RefreshRequest(REFRESH_TOKEN));

        assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().userId()).isEqualTo(123L);
        assertThat(response.user().roles()).containsExactly("ADMIN");
        // permissions 契约锚定：refresh 端点与 login 出参同构，会话权限集透传（PR-4D 填实）
        assertThat(response.user().permissions()).containsExactly("MENU /dashboard");
    }

    @Test
    @DisplayName("登录链路权限填实：roleService 展开的授权点集写入会话身份并经 toUserVO 透传出参（PR-4D）")
    void loginFillsPermissionsFromRoleExpansion() {
        UserEntity user = activeUser();
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);
        when(employeeMapper.selectOne(any())).thenReturn(employee(456L, "系统管理员", null));
        when(roleService.findRoleCodesByUserId(123L)).thenReturn(List.of("DOCTOR"));
        when(roleService.findPermissionCodesByUserId(123L)).thenReturn(PERMISSION_CODES);
        when(passwordEncoder.matches("Fuyun@2026", PASSWORD_HASH)).thenReturn(true);
        when(tokenService.issue(any(SessionUser.class))).thenReturn(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));

        LoginResponse response = authService.login(new LoginRequest(LOGIN_NAME, "Fuyun@2026"));

        // 会话身份携带角色展开的授权点集（ADMIN 特判/四步单表展开归 RoleServiceImpl，本类只断言接线）
        verify(tokenService).issue(sessionUserCaptor.capture());
        assertThat(sessionUserCaptor.getValue().permissions()).containsExactlyElementsOf(PERMISSION_CODES);
        // 出参透传：UserVO.permissions 经会话身份同名映射（AuthConverter 恒空集表达式已删）
        assertThat(response.user().permissions()).containsExactlyElementsOf(PERMISSION_CODES);
    }

    @Test
    @DisplayName("刷新链路权限透传：SessionData.permissions 重组进 SessionUser 并透传出参（PR-4D）")
    void refreshPassesSessionPermissionsThroughToUserVO() {
        SessionData session =
                new SessionData(123L, LOGIN_NAME, "系统管理员", 456L, null, List.of("DOCTOR"), null, PERMISSION_CODES);
        when(tokenService.refreshAccessToken(REFRESH_TOKEN)).thenReturn(new RefreshedAccess(ACCESS_TOKEN, session));

        LoginResponse response = authService.refresh(new RefreshRequest(REFRESH_TOKEN));

        assertThat(response.user().permissions()).containsExactlyElementsOf(PERMISSION_CODES);
    }

    @Test
    @DisplayName("旧会话缺 permissions 字段兼容：null 归一空清单出参（record 缺字段反序列化先例，PR-4D）")
    void refreshNormalizesNullPermissionsFromLegacySession() {
        // 旧会话 JSON 无 permissions 字段：反序列化为 null（升级窗口内既有会话），消费侧归一空清单
        SessionData legacySession =
                new SessionData(123L, LOGIN_NAME, "系统管理员", 456L, null, List.of("ADMIN"), null, null);
        when(tokenService.refreshAccessToken(REFRESH_TOKEN))
                .thenReturn(new RefreshedAccess(ACCESS_TOKEN, legacySession));

        LoginResponse response = authService.refresh(new RefreshRequest(REFRESH_TOKEN));

        assertThat(response.user().permissions()).isEmpty();
    }

    @Test
    @DisplayName("登出委派：剥离方案前缀的令牌原文交令牌服务校验并删会话")
    void logoutDelegatesRawTokenToTokenService() {
        authService.logout(REFRESH_TOKEN);

        verify(tokenService).logout(REFRESH_TOKEN);
    }

    @Test
    @DisplayName("大屏订阅令牌签发：哨兵匿名会话（零员工/零机构/零角色）+ 5 分钟短期 TTL + VO 组装（BUG-19）")
    void issueBigscreenTokenIssuesShortLivedAccessForSentinelSession() {
        when(tokenService.issueAccess(any(SessionUser.class), any(Duration.class)))
                .thenReturn(ACCESS_TOKEN);

        BigscreenTokenVO response = authService.issueBigscreenToken(null);

        // 哨兵身份：loginName=bigscreen 会话标记（W-39 P2 匿名只读通道演进锚点），不查库零权限面
        verify(tokenService).issueAccess(sessionUserCaptor.capture(), ttlCaptor.capture());
        SessionUser sessionUser = sessionUserCaptor.getValue();
        assertThat(sessionUser.userId()).isZero();
        assertThat(sessionUser.loginName()).isEqualTo("bigscreen");
        assertThat(sessionUser.displayName()).isEqualTo("候诊大屏");
        assertThat(sessionUser.employeeId()).isNull();
        assertThat(sessionUser.orgId()).isNull();
        assertThat(sessionUser.roles()).isEmpty();
        // 哨兵零权限面：permissions 空清单补位（403 面靠豁免挂码承载，不进会话权限集）
        assertThat(sessionUser.permissions()).isEmpty();
        // 短期 TTL 冻结：5 分钟（W-39 过渡期「缩短 TTL」用户裁决口径）
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofMinutes(5));
        // VO 组装：令牌值透传 + Bearer 方案名 + 有效期秒数换算（不包装 envelope）
        assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(Duration.ofMinutes(5).toSeconds());
    }

    @Test
    @DisplayName("携病区签发大屏令牌：哨兵会话 wardId 透传（W-39 通道锚点——REST 限行与 WS 订阅防线的比对源）")
    void issueBigscreenTokenCarriesWardIdIntoSentinelSession() {
        // 携病区签发：哨兵会话 wardId 透传（W-39 通道锚点——REST 限行与 WS 订阅防线的比对源）
        authService.issueBigscreenToken("1001");
        ArgumentCaptor<SessionUser> captor = ArgumentCaptor.forClass(SessionUser.class);
        verify(tokenService).issueAccess(captor.capture(), any());
        assertThat(captor.getValue().wardId()).isEqualTo("1001");
    }

    @Test
    @DisplayName("无病区签发大屏令牌：泛哨兵会话 wardId 归一 null（候诊屏无病区概念，空串会过字符串等值误匹配）")
    void issueBigscreenTokenWithoutWardIdYieldsNullWardSession() {
        // 泛哨兵（候诊屏 useQueueStomp 无病区概念）：wardId 归一 null 而非空串（空串会过字符串等值误匹配）
        authService.issueBigscreenToken(null);
        ArgumentCaptor<SessionUser> captor = ArgumentCaptor.forClass(SessionUser.class);
        verify(tokenService).issueAccess(captor.capture(), any());
        assertThat(captor.getValue().wardId()).isNull();
    }

    @Test
    @DisplayName("哨兵签发零权限面：permissions 空清单补位且不触角色/权限查询（PR-4D）")
    void issueBigscreenTokenGrantsEmptyPermissionFace() {
        when(tokenService.issueAccess(any(SessionUser.class), any(Duration.class)))
                .thenReturn(ACCESS_TOKEN);

        authService.issueBigscreenToken(null);

        // 哨兵不查库：角色摘要与权限集两查询均不触达（零权限面靠 D5 豁免挂码承载）
        verify(tokenService).issueAccess(sessionUserCaptor.capture(), any());
        assertThat(sessionUserCaptor.getValue().permissions()).isEmpty();
        verifyNoInteractions(roleService);
    }

    /** 构造 ACTIVE 状态的账号实体样本（含口令哈希与零失败计数） */
    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(123L);
        user.setLoginName(LOGIN_NAME);
        user.setPasswordHash(PASSWORD_HASH);
        user.setStatus(UserStatus.ACTIVE);
        user.setFailCount(0);
        return user;
    }

    /** 构造员工实体样本（user_id 与样本账号对应） */
    private EmployeeEntity employee(Long employeeId, String empName, Long primaryOrgId) {
        EmployeeEntity employee = new EmployeeEntity();
        employee.setId(employeeId);
        employee.setUserId(123L);
        employee.setEmpName(empName);
        employee.setPrimaryOrgId(primaryOrgId);
        return employee;
    }
}
