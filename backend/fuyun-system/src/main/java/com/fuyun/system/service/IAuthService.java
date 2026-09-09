package com.fuyun.system.service;

import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.vo.LoginResponse;

/**
 * 认证应用服务契约（登录/刷新/登出三用例编排，BRIEF-PR3-01 §1.3/§3.1）。
 *
 * <p>端点契约：login 与 refresh 白名单免认证，logout 需令牌（拦截器先做 401 认证）。
 * 失败一律抛 BizException（SYS-100x + HttpStatus），由全局渲染器输出 ProblemDetail。
 */
public interface IAuthService {

    /**
     * 登录：认证通过后签发双令牌并落 Redis 会话。
     *
     * <p>执行流程：加载账号 → 锁定校验 → 停用校验 → bcrypt 口令比对（失败计数状态机）→
     * 组装会话身份（员工反查 + 角色摘要）→ 签发令牌对。防枚举口径：登录名不存在与口令错误
     * 共用 SYS-1001 同文案。
     *
     * @param request 登录请求（loginName/password 非空由 @Valid 保证），非空
     * @return 登录响应（双令牌 + 用户身份），非空
     * @throws com.fuyun.common.exception.BizException SYS-1001（登录名或密码错误/账号不存在，401）、
     *                                                 SYS-1002（账号锁定中，401，文案含解锁时刻）、
     *                                                 SYS-1006（账号已停用，403）
     */
    LoginResponse login(LoginRequest request);

    /**
     * 刷新：以 refresh 令牌换发同 sid 的新 access 令牌（refresh 值不轮换，P0 口径）。
     *
     * @param request 刷新请求（refreshToken 非空由 @Valid 保证），非空
     * @return 登录响应（新 access + 原 refresh + 用户身份），非空
     * @throws com.fuyun.common.exception.BizException SYS-1005（刷新令牌无效/过期/会话不存在，401）
     */
    LoginResponse refresh(RefreshRequest request);

    /**
     * 登出：校验 typ=access 后删除会话键，双令牌同时失效。
     *
     * @param rawToken 访问令牌原文（Bearer 方案后的值，controller 已剥离方案前缀），非空
     * @throws com.fuyun.common.exception.BizException SYS-1003（令牌无效/会话不存在，401）、
     *                                                 SYS-1004（令牌已过期，401）
     */
    void logout(String rawToken);
}
