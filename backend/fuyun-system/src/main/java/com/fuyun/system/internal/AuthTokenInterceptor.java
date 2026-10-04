package com.fuyun.system.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.config.SystemWebConfig;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.record.SessionData;
import com.fuyun.system.service.ITokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器（D-2 受保护请求 401 契约，BRIEF-PR3-01 §1.3/§1.5）+ 哨兵 REST 限行（PR-4C W-39）。
 *
 * <p>落 fuyun-system 不下沉 common（简报 §1.5 裁决：令牌语义是 M01 领域契约，common 不感知业务域）。
 * 职责：preHandle 解析 Bearer 令牌 → tokenService 校验（typ=access）→ 哨兵限行判定 → 注入操作人
 * 上下文放行；任一认证失败直接写出 401 {@code application/problem+json}，哨兵越面/越区写出 403
 * （不经 GlobalExceptionHandler——拦截器无异常出口），body 结构与全局渲染同构：
 * {type, title, status, detail, errorCode, traceId}。
 *
 * <p>前置依赖：TraceIdFilter（HIGHEST_PRECEDENCE）先于本拦截器建立 MDC traceId，401/403 body 与日志均可携带。
 * 注册（addPathPatterns/excludePathPatterns 白名单）与 Bean 装配归 B3.2 的 SystemWebConfig/SystemConfig。
 *
 * <p>红线：日志禁打印令牌原文与签名——仅记 URI 与错误码。
 */
@Slf4j
public class AuthTokenInterceptor implements HandlerInterceptor {

    /** 令牌服务抽象：仅依赖接口（B.2-2 注入接口类型，禁注入实现类） */
    private final ITokenService tokenService;

    /** JSON 转换器：401 ProblemDetail 手工序列化（拦截器无异常出口，必须自行写响应） */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器。
     *
     * @param tokenService 令牌服务，非空；来源：模块装配（SystemWebConfig 经构造器注入）
     * @param objectMapper JSON 转换器，非空；与全局渲染同源（应用级 ObjectMapper）
     */
    public AuthTokenInterceptor(ITokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    /**
     * 认证前置校验：Bearer 令牌存在且校验通过才放行；哨兵令牌（loginName=bigscreen）额外经
     * allowlist 限行（W-39）。
     *
     * @param request  当前请求，非空
     * @param response 当前响应，非空；认证失败写 401 ProblemDetail，哨兵越面/越区写 403
     * @param handler  目标处理器（本拦截器不区分静态资源/控制器，一律要求令牌）
     * @return true 放行（操作人上下文已注入）；false 已拦截并写出 401/403
     * @throws IOException 响应写失败（容器级 IO 故障，交容器处理）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String header = request.getHeader(SecurityConstants.AUTH_HEADER);
        // 缺头或非 Bearer 方案：同归 SYS-1003（不区分"缺失"与"格式错"，防探测面收敛）
        if (header == null || !header.startsWith(SecurityConstants.BEARER_PREFIX)) {
            writeUnauthorized(request, response, SystemErrorCode.TOKEN_MISSING_OR_INVALID.getCode(), "令牌缺失或无效");
            return false;
        }
        String rawToken =
                header.substring(SecurityConstants.BEARER_PREFIX.length()).trim();
        try {
            SessionData session = tokenService.verify(rawToken, SecurityConstants.TOKEN_TYPE_ACCESS);
            // 校验通过注入操作人上下文（十进制字符串化 userId；审计切面与 created_by 注入读取，收尾必清）
            OperatorContextHolder.set(String.valueOf(session.userId()));
            // 角色清单同源注入：脱敏豁免（M02 IPrivacyMaskService）与 P1 鉴权拦截的统一数据源（SessionData.roles 非 null）
            RoleContextHolder.set(session.roles());
            // 哨兵限行（W-39）：大屏匿名令牌仅放行只读看板三端点且要求 wardId 一致——
            // 令牌本与登录 access 同构同权（IAuthService 演进注记的过渡态），此处把暴露面收窄到单病区只读
            if (SecurityConstants.BIGSCREEN_LOGIN_NAME.equals(session.loginName())
                    && !sentinelAllowed(request, session)) {
                // preHandle 返回 false 时 Spring 不回调本拦截器 afterCompletion（仅对 preHandle 成功者触发），
                // 已注入的两上下文须手动清理，防线程复用串号（与 afterCompletion 同款清理语义）
                OperatorContextHolder.clear();
                RoleContextHolder.clear();
                // 拒绝留痕（GC12）：记 wardId+uri+errorCode，禁打令牌内容
                log.warn(
                        "哨兵限行拒绝：uri={}，wardId={}，errorCode={}",
                        request.getRequestURI(),
                        session.wardId(),
                        SystemErrorCode.SENTINEL_ACCESS_DENIED.getCode());
                writeForbidden(
                        request, response, SystemErrorCode.SENTINEL_ACCESS_DENIED.getCode(), "大屏匿名令牌仅允许访问绑定病区的只读看板端点");
                return false;
            }
            return true;
        } catch (BizException ex) {
            // 校验链失败（SYS-1003/1004）：按异常自带错误码原样转写 401
            writeUnauthorized(request, response, ex.getErrorCode().getCode(), ex.getMessage());
            return false;
        }
    }

    /**
     * 请求收尾：finally 语义清理操作人与角色上下文，防线程复用残留串号（两上下文类契约）。
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        OperatorContextHolder.clear();
        RoleContextHolder.clear();
    }

    /**
     * 手工写出 401 ProblemDetail（结构与 GlobalExceptionHandler 渲染同构）。
     *
     * <p>必须先设字符编码再取 Writer：容器默认 ISO-8859-1 会损坏中文 detail 文案。
     *
     * @param request   当前请求，非空；仅取 URI 做告警日志
     * @param response  当前响应，非空
     * @param errorCode 业务错误码字符串（SYS-1003/SYS-1004），非空
     * @param detail    业务可读文案，非空；禁携带令牌内容
     * @throws IOException 响应写失败，交容器处理
     */
    private void writeUnauthorized(
            HttpServletRequest request, HttpServletResponse response, String errorCode, String detail)
            throws IOException {
        log.warn("认证拦截拒绝：uri={}，errorCode={}，detail={}", request.getRequestURI(), errorCode, detail);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        body.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase());
        body.setProperty("errorCode", errorCode);
        // traceId 取 TraceIdFilter 置入的 MDC 值（与响应头 X-Trace-Id 同源，排障锚点一致）
        body.setProperty("traceId", MDC.get(SecurityConstants.TRACE_ID_MDC_KEY));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * 哨兵限行判定（W-39）：按 allowlist 三端点校验请求路径/参数与会话病区的一致性。
     *
     * <p>规则：board 与 infusion-board 前缀端点要求路径尾段（即病区编码）与会话 wardId 字符串等值；
     * iot/alarms 精确端点要求 query 参数 wardId 等值。泛哨兵（会话未携病区）无一致可校，一律拒
     * （fail-closed）；不在 allowlist 的任意路径同样拒绝（写面/其他读面收敛到登录态）。
     *
     * @param request 当前请求，非空；URI 与 query 参数即比对目标
     * @param session 已通过校验的哨兵会话，非空
     * @return true 放行（路径与病区一致）；false 拒绝（越面/越区/泛哨兵）
     */
    private boolean sentinelAllowed(HttpServletRequest request, SessionData session) {
        String uri = request.getRequestURI();
        String ward = session.wardId();
        if (ward == null || ward.isBlank()) {
            // 泛哨兵（未携病区签发）：无一致可校，一律拒（fail-closed）
            return false;
        }
        for (String prefix : SystemWebConfig.SENTINEL_ALLOWLIST) {
            if (uri.equals(prefix)) {
                // 精确端点（iot/alarms）：wardId 走 query 参数等值比对
                return ward.equals(request.getParameter("wardId"));
            }
            if (uri.startsWith(prefix)) {
                // 前缀端点（board/infusion-board）：尾段即路径病区编码，字符串等值比对（双标识空间自洽，W-74 不在此收敛）
                return ward.equals(uri.substring(prefix.length()));
            }
        }
        return false;
    }

    /**
     * 手工写出 403 ProblemDetail（结构与 writeUnauthorized/全局渲染同构，仅状态与语义不同）。
     *
     * <p>必须先设字符编码再取 Writer：容器默认 ISO-8859-1 会损坏中文 detail 文案。
     *
     * @param request   当前请求，非空；仅取 URI 做告警日志
     * @param response  当前响应，非空
     * @param errorCode 业务错误码字符串（SYS-1032），非空
     * @param detail    业务可读文案，非空；禁携带令牌内容
     * @throws IOException 响应写失败，交容器处理
     */
    private void writeForbidden(
            HttpServletRequest request, HttpServletResponse response, String errorCode, String detail)
            throws IOException {
        log.warn("哨兵限行拒绝：uri={}，errorCode={}，detail={}", request.getRequestURI(), errorCode, detail);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, detail);
        body.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
        body.setProperty("errorCode", errorCode);
        // traceId 取 TraceIdFilter 置入的 MDC 值（与响应头 X-Trace-Id 同源，排障锚点一致）
        body.setProperty("traceId", MDC.get(SecurityConstants.TRACE_ID_MDC_KEY));
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
