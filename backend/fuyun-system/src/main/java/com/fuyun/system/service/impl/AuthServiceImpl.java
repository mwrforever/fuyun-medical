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
import com.fuyun.system.vo.BigscreenTokenVO;
import com.fuyun.system.vo.LoginResponse;
import java.time.Duration;
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

    /** 大屏匿名会话哨兵登录名：会话标记（W-39 P2 演进为专用匿名只读通道的锚点），不对应 sys_user 行 */
    private static final String BIGSCREEN_LOGIN_NAME = "bigscreen";

    /** 大屏匿名会话哨兵显示名：审计/排障可读载体（脱敏出网，非敏感字段） */
    private static final String BIGSCREEN_DISPLAY_NAME = "候诊大屏";

    /**
     * 大屏匿名会话哨兵 userId：0 不与雪花 ID 冲突（正数域）；经会话进操作人上下文时以 "0" 呈现，
     * P2 专用通道落地后随 W-39 收敛。
     */
    private static final long BIGSCREEN_SENTINEL_USER_ID = 0L;

    /**
     * 大屏订阅令牌 TTL：短期凭证（W-39 2026-09-25 用户裁决「缩短 TTL」过渡期口径）。令牌仅承载
     * STOMP CONNECT 帧鉴权，失效由前端按次重签（换发成本为一次匿名 HTTP），不设 refresh。
     */
    private static final Duration BIGSCREEN_TOKEN_TTL = Duration.ofMinutes(5);

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

    /**
     * 登录用例编排（认证链路入口）：账号加载 → 锁定校验（先于口令比对，防锁定期间继续累加
     * 失败计数）→ 停用校验 → bcrypt 口令比对（失败走 recordLoginFailure 状态机）→ 成功走
     * recordLoginSuccess 复位 → 组装会话身份（员工反查 + 角色摘要）→ 签发双令牌。
     *
     * <p>防枚举口径：账号不存在与口令错误共用 SYS-1001 同文案，禁止差异文案探测账号存在性。
     *
     * @param request 登录请求，非空；loginName/password 非空由 controller 层 @Valid 保证
     * @return 登录响应（双令牌 + 用户身份 VO），非空
     * @throws BizException SYS-1001（登录名或密码错误/账号不存在，401，防枚举同文案）、
     *                      SYS-1002（账号锁定中，401，文案含解锁时刻）、SYS-1006（账号已停用，403）
     */
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

    /**
     * 刷新用例编排：以 refresh 令牌换发同 sid 的新 access 令牌（校验成功即滑动续期，refresh
     * 值不轮换为 P0 口径）。
     *
     * <p>换发校验收敛于令牌服务（typ=refresh 强校验，失败统一 SYS-1005 不透出细分）；本方法
     * 仅从会话状态重组会话身份并组装响应（refresh 原值回填，前端覆盖存储时值未变）。
     *
     * @param request 刷新请求，非空；refreshToken 非空由 controller 层 @Valid 保证
     * @return 登录响应（新 access + 原 refresh + 用户身份 VO），非空
     * @throws BizException SYS-1005（刷新令牌无效/过期/会话不存在，401）
     */
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
                session.roles(),
                null);
        log.info("刷新换发成功：userId={}", session.userId());
        // refresh 值原样回填（P0 不轮换）：前端以响应中 refreshToken 覆盖存储，值未变
        return new LoginResponse(
                refreshed.accessToken(),
                request.refreshToken(),
                SecurityConstants.BEARER_PREFIX.trim(),
                securityProperties.accessTokenTtl().toSeconds(),
                authConverter.toUserVO(sessionUser));
    }

    /**
     * 登出用例编排：typ=access 校验链通过后按令牌内 sid 删除会话键（access 与 refresh 同 sid
     * 同时失效，"删除即全端失效"语义）。
     *
     * <p>校验与删键动作收敛于令牌服务（防伪造/过期令牌触发删除探测）；本方法仅承载用例编排
     * 与留痕。
     *
     * @param rawToken 访问令牌原文（controller 已剥离 Bearer 方案前缀），非空
     * @throws BizException SYS-1003（令牌无效/会话不存在，401）、SYS-1004（令牌已过期，401）
     */
    @Override
    public void logout(String rawToken) {
        // typ=access 校验链通过后删会话键（sid 为令牌内部字段，删除动作收敛于令牌服务）
        tokenService.logout(rawToken);
        log.info("登出完成，会话已失效");
    }

    /**
     * 大屏订阅令牌签发（BUG-19）：匿名哨兵会话（可携病区编码收窄授权面）+ 5 分钟短期单 access 令牌。
     *
     * <p>哨兵身份零权限面（零角色/零员工/零机构），不查库不写用户状态机；wardId 原样透传入会话
     * （空白归一在 controller 层完成，本层不做二次归一）；签发留痕经令牌服务 info 日志承载（sid，
     * 禁令牌值）。演进注记与安全边界见 {@link IAuthService#issueBigscreenToken}。
     *
     * @param wardId 病区编码，可 null（泛哨兵=候诊屏用）；携病区时经会话承载为限行/订阅防线比对源
     * @return 大屏订阅令牌出参（令牌值 + Bearer 方案名 + 有效期秒数），非空
     */
    @Override
    public BigscreenTokenVO issueBigscreenToken(String wardId) {
        // 哨兵会话末参透传 wardId 原样：携病区=病区屏专用，null=泛哨兵（REST 限行与 WS 订阅防线的比对源）
        SessionUser screen = new SessionUser(
                BIGSCREEN_SENTINEL_USER_ID,
                BIGSCREEN_LOGIN_NAME,
                BIGSCREEN_DISPLAY_NAME,
                null,
                null,
                List.of(),
                wardId);
        String accessToken = tokenService.issueAccess(screen, BIGSCREEN_TOKEN_TTL);
        log.info(
                "大屏订阅令牌已签发：loginName={}，wardId={}，ttl={}s（匿名哨兵会话，令牌值禁入日志）",
                BIGSCREEN_LOGIN_NAME,
                wardId,
                BIGSCREEN_TOKEN_TTL.toSeconds());
        return new BigscreenTokenVO(
                accessToken, SecurityConstants.BEARER_PREFIX.trim(), BIGSCREEN_TOKEN_TTL.toSeconds());
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
                roles,
                // wardId=null：登录态会话不携病区（哨兵签发面专属）
                null);
    }
}
