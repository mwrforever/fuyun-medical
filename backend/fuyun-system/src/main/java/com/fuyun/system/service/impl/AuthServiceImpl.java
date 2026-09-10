package com.fuyun.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.constants.SecurityConstants;
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
import com.fuyun.system.service.IAuthService;
import com.fuyun.system.service.IRoleService;
import com.fuyun.system.service.ITokenService;
import com.fuyun.system.service.IUserService;
import com.fuyun.system.vo.LoginResponse;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证应用服务实现（登录/刷新/登出三用例编排，M01 FU-M01-01/02 认证链路核心路径）。
 *
 * <p>login 执行流程：加载账号 → 锁定校验（locked_until 未到期拒绝，先于口令比对防锁定期间
 * 继续累加计数）→ 停用校验 → bcrypt 口令比对（失败走 recordLoginFailure 状态机）→ 成功走
 * recordLoginSuccess 复位 → 员工反查 + 角色摘要组装会话身份 → 令牌服务签发。防枚举红线：
 * 账号不存在与口令错误共用 SYS-1001 同文案；日志禁打印口令与口令哈希。
 *
 * <p>事务边界：登录写路径（recordLoginFailure/recordLoginSuccess）在用户服务方法级各自成事务
 * （A.4.2-7 最小边界），本类查询与令牌签发无事务需求；令牌校验/签发为无状态纯操作。
 *
 * <p>线程安全：无状态单例（全部依赖为注入的无状态 Bean）。
 */
@Slf4j
public class AuthServiceImpl implements IAuthService {

    /** 登录失败防枚举文案：账号不存在与口令错误共用（安全红线 §8-11，禁差异文案探测账号存在性） */
    private static final String LOGIN_FAIL_DETAIL = "登录名或密码错误";

    private final IUserService userService;

    private final IRoleService roleService;

    private final EmployeeMapper employeeMapper;

    private final ITokenService tokenService;

    private final PasswordEncoder passwordEncoder;

    private final AuthConverter authConverter;

    private final SecurityProperties securityProperties;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1；本类不加 stereotype 注解）。
     *
     * @param userService        用户账号服务，非空；登录状态机写入口
     * @param roleService        角色服务，非空；会话角色摘要查询
     * @param employeeMapper     员工表 mapper，非空；员工身份反查（user_id 一对一）
     * @param tokenService       令牌服务，非空；签发/校验/登出
     * @param passwordEncoder    bcrypt 口令编码器，非空；来源：SystemWebConfig Bean
     * @param authConverter      认证域转换器，非空；响应组装
     * @param securityProperties 安全配置，非空；expiresIn 取 access TTL
     */
    public AuthServiceImpl(
            IUserService userService,
            IRoleService roleService,
            EmployeeMapper employeeMapper,
            ITokenService tokenService,
            PasswordEncoder passwordEncoder,
            AuthConverter authConverter,
            SecurityProperties securityProperties) {
        this.userService = userService;
        this.roleService = roleService;
        this.employeeMapper = employeeMapper;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.authConverter = authConverter;
        this.securityProperties = securityProperties;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        // 1. 加载账号：不存在按 SYS-1001 拒绝（与口令错误同文案，防用户枚举）
        UserEntity user = userService.findByLoginName(request.loginName());
        if (user == null) {
            log.warn("登录失败（账号不存在）：loginName={}", request.loginName());
            throw new BizException(
                    SystemErrorCode.LOGIN_NAME_OR_PASSWORD_WRONG, HttpStatus.UNAUTHORIZED, LOGIN_FAIL_DETAIL);
        }
        // 2. 锁定校验先于口令比对：锁定期间拒绝重试（防继续累加失败计数干扰自动到期恢复）
        OffsetDateTime now = OffsetDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            log.warn("登录被拒（账号锁定中）：loginName={}，lockedUntil={}", user.getLoginName(), user.getLockedUntil());
            throw new BizException(
                    SystemErrorCode.ACCOUNT_LOCKED,
                    HttpStatus.UNAUTHORIZED,
                    "账号已锁定，请于 " + user.getLockedUntil() + " 后重试");
        }
        // 3. 停用校验：停用账号拒绝登录（SYS-1006/403）
        if (user.getStatus() == UserStatus.DISABLED) {
            log.warn("登录被拒（账号停用）：loginName={}", user.getLoginName());
            throw new BizException(SystemErrorCode.ACCOUNT_DISABLED, HttpStatus.FORBIDDEN, "账号已停用，请联系管理员");
        }
        // 4. 口令比对：失败走失败计数状态机（达阈值置锁定），仍按 SYS-1001 防枚举文案拒绝
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            userService.recordLoginFailure(user);
            log.warn("登录失败（口令不匹配）：loginName={}", user.getLoginName());
            throw new BizException(
                    SystemErrorCode.LOGIN_NAME_OR_PASSWORD_WRONG, HttpStatus.UNAUTHORIZED, LOGIN_FAIL_DETAIL);
        }
        // 5. 成功复位状态机：计数清零 + 锁定清空 + 最近登录时刻
        userService.recordLoginSuccess(user);
        // 6. 组装会话身份并签发双令牌（会话落 Redis，角色摘要存会话不进令牌体）
        SessionUser sessionUser = buildSessionUser(user);
        TokenPair pair = tokenService.issue(sessionUser);
        log.info("登录成功：loginName={}，userId={}", user.getLoginName(), user.getId());
        return authConverter.toLoginResponse(
                pair,
                SecurityConstants.BEARER_PREFIX.trim(),
                securityProperties.accessTokenTtl().toSeconds(),
                authConverter.toUserVO(sessionUser));
    }

    @Override
    public LoginResponse refresh(RefreshRequest request) {
        // 换发收敛于令牌服务：typ=refresh 强校验 + 同 sid 新 access（校验成功即滑动续期），失败统一 SYS-1005
        RefreshedAccess refreshed = tokenService.refreshAccessToken(request.refreshToken());
        SessionData session = refreshed.session();
        SessionUser sessionUser = new SessionUser(
                session.userId(),
                session.loginName(),
                session.displayName(),
                session.employeeId(),
                session.orgId(),
                session.roles());
        log.info("刷新换发成功：userId={}", session.userId());
        // refresh 值原样回填（P0 不轮换）：前端以响应中 refreshToken 覆盖存储，值未变
        return new LoginResponse(
                refreshed.accessToken(),
                request.refreshToken(),
                SecurityConstants.BEARER_PREFIX.trim(),
                securityProperties.accessTokenTtl().toSeconds(),
                authConverter.toUserVO(sessionUser));
    }

    @Override
    public void logout(String rawToken) {
        // typ=access 校验链通过后删会话键（sid 为令牌内部字段，删除动作收敛于令牌服务）
        tokenService.logout(rawToken);
        log.info("登出完成，会话已失效");
    }

    /**
     * 组装登录会话身份：员工反查（eid/orgId/displayName）+ 角色摘要。
     *
     * @param user 已通过认证的账号实体，非空
     * @return 会话身份入参，非空；无员工行的系统/接口账号 eid/orgId 为 null，displayName 以登录名兜底
     */
    private SessionUser buildSessionUser(UserEntity user) {
        // 员工反查（sys_employee.user_id 一对一，select 精确投影 A.4.3-14）
        EmployeeEntity employee = employeeMapper.selectOne(Wrappers.<EmployeeEntity>lambdaQuery()
                .eq(EmployeeEntity::getUserId, user.getId())
                .select(EmployeeEntity::getId, EmployeeEntity::getEmpName, EmployeeEntity::getPrimaryOrgId));
        List<String> roles = roleService.findRoleCodesByUserId(user.getId());
        String displayName =
                (employee != null && employee.getEmpName() != null) ? employee.getEmpName() : user.getLoginName();
        return new SessionUser(
                user.getId(),
                user.getLoginName(),
                displayName,
                employee != null ? employee.getId() : null,
                employee != null ? employee.getPrimaryOrgId() : null,
                roles);
    }
}
