package com.fuyun.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * traceId 请求过滤器：全链路日志关联的起点。
 *
 * <p>执行流程：读取前端拦截器注入的 X-Trace-Id 请求头（web 宪法 A.3-2）→ 缺失则生成 UUID →
 * 写入 MDC（logging pattern 的 {@code %X{traceId}} 据此输出）→ 按开关回写 X-Trace-Id 响应头 →
 * finally 清理 MDC，防止容器工作线程复用串号。
 *
 * <p>不加 {@code @Component}：由 fuyun-app 配置类经 FilterRegistrationBean 注册，
 * 取最高优先级保证先于其他过滤器生效（backend 宪法 B.1 装配归 app）。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** 追踪头名称：请求与响应同名（前端注入 / 响应回写排障锚点） */
    public static final String TRACE_HEADER = "X-Trace-Id";

    /** MDC 键名：来源为 fuyun.trace.mdc-key 配置，默认 traceId */
    private final String mdcKey;

    /** 是否回写 X-Trace-Id 响应头：来源为 fuyun.trace.response-header-enabled 配置 */
    private final boolean responseHeaderEnabled;

    /**
     * 全参构造器。
     *
     * @param mdcKey                MDC 键名，非空；来源：fuyun.trace.mdc-key 配置（默认 traceId），
     *                              须与 GlobalExceptionHandler 读取的键一致
     * @param responseHeaderEnabled 是否回写 X-Trace-Id 响应头；true 用于前端/网关联动排障
     */
    public TraceIdFilter(String mdcKey, boolean responseHeaderEnabled) {
        this.mdcKey = mdcKey;
        this.responseHeaderEnabled = responseHeaderEnabled;
    }

    /**
     * 过滤主逻辑：建立或继承 traceId，并保证请求结束后 MDC 必然清理。
     *
     * @param request     当前请求，非空
     * @param response    当前响应，非空
     * @param filterChain 下游过滤器链，非空
     * @throws ServletException 下游处理异常，原样上抛由容器处理
     * @throws IOException      下游 IO 异常，原样上抛
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 复用上游注入的 traceId 保持全链路同一锚点；缺失时兜底生成，保证每个请求必有追踪标识
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(mdcKey, traceId);
        try {
            if (responseHeaderEnabled) {
                response.setHeader(TRACE_HEADER, traceId);
            }
            filterChain.doFilter(request, response);
        } finally {
            // Tomcat 工作线程复用，不清理会让下个请求带上脏 traceId
            MDC.remove(mdcKey);
        }
    }
}
