package com.fuyun.system.controller;

import com.fuyun.system.api.AuditActionType;
import com.fuyun.system.api.AuditLog;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.dto.LoginRequest;
import com.fuyun.system.dto.RefreshRequest;
import com.fuyun.system.service.IAuthService;
import com.fuyun.system.vo.BigscreenTokenVO;
import com.fuyun.system.vo.LoginResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点（POST /api/v1/system/auth/*，BRIEF-PR3-01 §1.3 三操作契约）。
 *
 * <p>职责边界：仅入参校验（@Valid）+ 调用 service + 编排响应，禁业务逻辑与事务
 * （宪法 B.1/A.1-8）。login/refresh 在 SystemWebConfig 白名单内免认证；logout 受
 * AuthTokenInterceptor 保护（缺令牌/令牌无效在拦截器层 401）。
 */
@RestController
@Validated
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
     * <p>审计落点（LOGIN）：成功记 SUCCESS 行、失败记 FAIL 行（含防枚举失败与锁定拒绝），
     * 落库与脱敏语义归 AuditLogAspect；免认证端点无操作人上下文，审计主体回退取入参登录名。
     *
     * @param request 登录请求，非空；loginName/password 由 JSR-303 校验非空
     * @return 登录响应（双令牌 + 用户身份，userId/orgId 以 JSON 字符串输出）
     */
    @PostMapping("/login")
    @AuditLog(actionType = AuditActionType.LOGIN)
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
     * <p>审计落点（LOGIN）：会话类安全动作留痕，操作人取认证拦截器注入的上下文。
     *
     * @param authorization Authorization 请求头，非空（拦截器已保证 Bearer 方案合法）
     * @return 204 无响应体
     */
    @PostMapping("/logout")
    @AuditLog(actionType = AuditActionType.LOGIN)
    public ResponseEntity<Void> logout(@RequestHeader(SecurityConstants.AUTH_HEADER) String authorization) {
        // 剥离 Bearer 方案前缀取令牌原文（拦截器已校验前缀存在，此处安全）
        authService.logout(authorization
                .substring(SecurityConstants.BEARER_PREFIX.length())
                .trim());
        return ResponseEntity.noContent().build();
    }

    /**
     * 大屏订阅令牌签发（匿名，BUG-19，可携病区编码收窄授权面）：候诊叫号大屏 WS 链路凭证的运行期获取入口。
     *
     * <p>白名单免认证（大屏无登录态设备直开，同队列快照匿名只读口径）：返回 5 分钟短期单
     * access 令牌，前端注入 /ws/outpatient STOMP CONNECT 帧鉴权——替代已删除的构建期
     * VITE_BIGSCREEN_TOKEN 内联（web 宪法 A.2-2 红线）。安全边界与 P2 演进注记见
     * {@link com.fuyun.system.service.IAuthService#issueBigscreenToken}。
     *
     * <p>不落 @AuditLog：匿名高频机器签发（断线重连每次连接尝试重签），审计行会随重连风暴
     * 刷表；签发留痕经令牌服务 info 日志（sid）承载，限流/风控随 M18 治理（裁决 13 注记同口径）。
     *
     * @param wardId 病区编码，可空；携病区收窄授权面（病区屏专用，W-39 通道锚点），空白归一 null
     *               （泛哨兵=候诊屏无病区概念）；仅允许字母数字下划线连字符，长度 1-64
     * @return 大屏订阅令牌出参（令牌值 + Bearer 方案名 + 有效期秒数），非空
     */
    @PostMapping("/bigscreen-token")
    public BigscreenTokenVO bigscreenToken(
            @RequestParam(value = "wardId", required = false)
                    @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "病区编码仅允许字母数字下划线连字符，长度 1-64")
                    String wardId) {
        // 空白 wardId 归一 null：泛哨兵会话 wardId 恒 null（空串会过字符串等值误匹配限行防线）
        return authService.issueBigscreenToken(wardId == null || wardId.isBlank() ? null : wardId.trim());
    }
}
