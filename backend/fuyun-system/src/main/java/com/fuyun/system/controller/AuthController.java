package com.fuyun.system.controller;

import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.service.IAuthService;
import com.fuyun.system.vo.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（POST /api/v1/system/auth/*，BRIEF-PR3-01 §1.3 三操作契约）。
 *
 * <p>职责边界：仅入参校验（@Valid）+ 调用 service + 编排响应，禁业务逻辑与事务
 * （宪法 B.1/A.1-8）。login/refresh 在 SystemWebConfig 白名单内免认证；logout 受
 * AuthTokenInterceptor 保护（缺令牌/令牌无效在拦截器层 401）。
 */
@RestController
@RequestMapping("/api/v1/system/auth")
public class AuthController {

    private final IAuthService authService;

    /**
     * 全参构造器（装配归 SystemWebConfig @Import，backend 宪法 B.1）。
     *
     * @param authService 认证应用服务，非空；注入接口类型（B.2-2）
     */
    public AuthController(IAuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录：用户名 + 口令换双令牌。
     *
     * @param request 登录请求，非空；loginName/password 由 JSR-303 校验非空
     * @return 登录响应（双令牌 + 用户身份，userId/orgId 以 JSON 字符串输出）
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * 刷新：refresh 令牌换发新 access 令牌（refresh 值不轮换）。
     *
     * @param request 刷新请求，非空；refreshToken 由 JSR-303 校验非空
     * @return 登录响应（新 access + 原 refresh + 用户身份）
     */
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    /**
     * 登出：删除当前令牌会话，双令牌同时失效。
     *
     * @param authorization Authorization 请求头，非空（拦截器已保证 Bearer 方案合法）
     * @return 204 无响应体
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(SecurityConstants.AUTH_HEADER) String authorization) {
        // 剥离 Bearer 方案前缀取令牌原文（拦截器已校验前缀存在，此处安全）
        authService.logout(authorization
                .substring(SecurityConstants.BEARER_PREFIX.length())
                .trim());
        return ResponseEntity.noContent().build();
    }
}
