package com.fuyun.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * （SYS-1006/403）、无员工行账号的会话身份兜底、refresh 换发（原 refresh 值回填）、logout 委派。
 * 依赖以 Mockito 模拟；AuthConverter 用 MapStruct 生成实现（转换逻辑一并断言）。
 * 端到端 HTTP 链路归 B3.3 集成测试。
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
    @DisplayName("成功登录：状态机复位 + 双令牌签发 + 响应组装（身份/角色/有效期逐项断言）")
    void loginSucceedsAndIssuesTokenPairWithSessionReset() {
        UserEntity user = activeUser();
        when(userService.findByLoginName(LOGIN_NAME)).thenReturn(user);
        when(employeeMapper.selectOne(any())).thenReturn(employee(456L, "系统管理员", null));
        when(roleService.findRoleCodesByUserId(123L)).thenReturn(List.of("ADMIN"));
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

        // 成功路径复位状态机（失败计数清零/锁定清空/最近登录时刻），签发入参为组装后的会话身份
        verify(userService).recordLoginSuccess(user);
        verify(userService, never()).recordLoginFailure(any());
        verify(tokenService).issue(sessionUserCaptor.capture());
        SessionUser issued = sessionUserCaptor.getValue();
        assertThat(issued.userId()).isEqualTo(123L);
        assertThat(issued.displayName()).isEqualTo("系统管理员");
        assertThat(issued.employeeId()).isEqualTo(456L);
        assertThat(issued.roles()).containsExactly("ADMIN");
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
        when(passwordEncoder.matches("Fuyun@2026", PASSWORD_HASH)).thenReturn(true);
        when(tokenService.issue(any(SessionUser.class))).thenReturn(new TokenPair(ACCESS_TOKEN, REFRESH_TOKEN));

        LoginResponse response = authService.login(new LoginRequest(LOGIN_NAME, "Fuyun@2026"));

        assertThat(response.user().displayName()).isEqualTo(LOGIN_NAME);
        assertThat(response.user().roles()).isEmpty();
        verify(tokenService).issue(sessionUserCaptor.capture());
        assertThat(sessionUserCaptor.getValue().employeeId()).isNull();
        assertThat(sessionUserCaptor.getValue().orgId()).isNull();
    }

    @Test
    @DisplayName("刷新换发：新 access + 原 refresh 值回填（P0 不轮换）+ 会话身份还原")
    void refreshReturnsMintedAccessWithOriginalRefreshToken() {
        SessionData session = new SessionData(123L, LOGIN_NAME, "系统管理员", 456L, null, List.of("ADMIN"));
        when(tokenService.refreshAccessToken(REFRESH_TOKEN)).thenReturn(new RefreshedAccess(ACCESS_TOKEN, session));

        LoginResponse response = authService.refresh(new RefreshRequest(REFRESH_TOKEN));

        assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().userId()).isEqualTo(123L);
        assertThat(response.user().roles()).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("登出委派：剥离方案前缀的令牌原文交令牌服务校验并删会话")
    void logoutDelegatesRawTokenToTokenService() {
        authService.logout(REFRESH_TOKEN);

        verify(tokenService).logout(REFRESH_TOKEN);
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
