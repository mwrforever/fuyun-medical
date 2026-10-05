package com.fuyun.system.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.context.RoleContextHolder;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.constants.SecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 403 鉴权拦截器（PR-4D W-37 主体，D3/D4 裁定承载）：认证拦截器之后的第二道闸，
 * 按 {@link PermissionRegistry} 登记面校验会话角色与权限点允许集的交集。
 *
 * <p>判定顺序（优先级从高到低）：
 * ①会话角色含 ADMIN → 一票放行（D3 超管运行期全放，不种绑定行）；②矩阵未登记 →
 * 放行 + warn 留痕（D4 医疗可用性优先——运行期全量 fail-closed 有全站锁死风险，
 * 端点登记完整性由 RbacMatrixIT 全量对照断言守护）；③命中权限点 → 允许集与会话角色
 * 交集非空放行，空则写出 403 {@code application/problem+json}（errorCode=SYS-1033，
 * body 结构与 AuthTokenInterceptor 拒绝面同构：{type,title,status,detail,errorCode,traceId}）。
 *
 * <p>上下文契约：只读 {@link RoleContextHolder}（由前置 AuthTokenInterceptor 注入并
 * 在 afterCompletion 统一清理），本拦截器<b>不实现 afterCompletion</b>——无注入即无
 * 清理职责，双层清理反而引入错序风险。
 *
 * <p>红线：日志仅记 uri 与错误码（含固定 detail），禁打令牌内容与角色清单外敏感信息。
 */
@Slf4j
public class AuthorizationInterceptor implements HandlerInterceptor {

    /** 鉴权矩阵登记面：仅读（装载与刷新归 PermissionRegistry 生命周期） */
    private final PermissionRegistry registry;

    /** JSON 转换器：403 ProblemDetail 手工序列化（拦截器无异常出口，必须自行写响应） */
    private final ObjectMapper objectMapper;

    /**
     * 全参构造器（装配归 SystemWebConfig，构造器注入宪法 A.1-7）。
     *
     * @param registry      鉴权矩阵登记面，非空；来源：SystemWebConfig @Bean 装配并已完成启动装载
     * @param objectMapper JSON 转换器，非空；与全局渲染同源（应用级 ObjectMapper）
     */
    public AuthorizationInterceptor(PermissionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 鉴权前置校验：ADMIN 一票放行 → 未登记放行（warn）→ 允许集与会话角色交集判定。
     *
     * @param request  当前请求，非空；角色上下文已由前置认证拦截器注入
     * @param response 当前响应，非空；角色不符时写出 403 ProblemDetail
     * @param handler  目标处理器（本拦截器不区分类型，只按矩阵判定）
     * @return true 放行；false 已拦截并写出 403
     * @throws IOException 响应写失败（容器级 IO 故障，交容器处理）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        List<String> sessionRoles = RoleContextHolder.get();
        // ADMIN 一票放行（D3）：优先级最高，未登记/越权端点均通行（矩阵解析都不必触达）
        if (sessionRoles.contains(SecurityConstants.ADMIN_ROLE_CODE)) {
            return true;
        }
        Optional<PermissionRegistry.PermissionEntry> entry =
                registry.resolve(request.getMethod(), request.getRequestURI());
        if (entry.isEmpty()) {
            // 未登记面放行（D4 医疗可用性优先）：warn 留痕供登记缺口巡检（RbacMatrixIT 为门禁面）
            log.warn("403矩阵未登记路径放行：uri={}", request.getRequestURI());
            return true;
        }
        // 命中权限点：允许集与会话角色交集判定（空集会话——哨兵——与任何允许集交集必空）
        boolean permitted = entry.get().allowedRoles().stream().anyMatch(sessionRoles::contains);
        if (permitted) {
            return true;
        }
        writeForbidden(request, response, SystemErrorCode.PERMISSION_DENIED.getCode(), "无权访问该功能（权限不足）");
        return false;
    }

    /**
     * 手工写出 403 ProblemDetail（结构与 AuthTokenInterceptor.writeForbidden/全局渲染同构，仅 errorCode 语义不同）。
     *
     * <p>必须先设字符编码再取 Writer：容器默认 ISO-8859-1 会损坏中文 detail 文案。
     *
     * @param request   当前请求，非空；仅取 URI 做告警日志
     * @param response  当前响应，非空
     * @param errorCode 业务错误码字符串（SYS-1033），非空
     * @param detail    业务可读文案，非空；禁携带令牌与角色清单
     * @throws IOException 响应写失败，交容器处理
     */
    private void writeForbidden(
            HttpServletRequest request, HttpServletResponse response, String errorCode, String detail)
            throws IOException {
        // 拒绝留痕：仅 uri+errorCode+detail（纪律 5：禁打角色清单外的敏感内容）
        log.warn("鉴权拦截拒绝：uri={}，errorCode={}，detail={}", request.getRequestURI(), errorCode, detail);
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
