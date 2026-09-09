package com.fuyun.system.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.OperatorContextHolder;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
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
 * 认证拦截器（D-2 受保护请求 401 契约，BRIEF-PR3-01 §1.3/§1.5）。
 *
 * <p>落 fuyun-system 不下沉 common（简报 §1.5 裁决：令牌语义是 M01 领域契约，common 不感知业务域）。
 * 职责：preHandle 解析 Bearer 令牌 → tokenService 校验（typ=access）→ 注入操作人上下文放行；
 * 任一失败直接写出 401 {@code application/problem+json}（不经 GlobalExceptionHandler——拦截器
 * 无异常出口），body 结构与全局渲染同构：{type, title, status, detail, errorCode, traceId}。
 *
 * <p>前置依赖：TraceIdFilter（HIGHEST_PRECEDENCE）先于本拦截器建立 MDC traceId，401 body 与日志均可携带。
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
     * 认证前置校验：Bearer 令牌存在且校验通过才放行。
     *
     * @param request  当前请求，非空
     * @param response 当前响应，非空；失败场景写 401 ProblemDetail
     * @param handler  目标处理器（本拦截器不区分静态资源/控制器，一律要求令牌）
     * @return true 放行（操作人上下文已注入）；false 已拦截并写出 401
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
            return true;
        } catch (BizException ex) {
            // 校验链失败（SYS-1003/1004）：按异常自带错误码原样转写 401
            writeUnauthorized(request, response, ex.getErrorCode().getCode(), ex.getMessage());
            return false;
        }
    }

    /**
     * 请求收尾：finally 语义清理操作人上下文，防线程复用残留串号（OperatorContextHolder 契约）。
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        OperatorContextHolder.clear();
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
}
