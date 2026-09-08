package com.fuyun.app.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * traceId 链路配置（@ConfigurationProperties 示范：record 构造器绑定 + 启动期 JSR-303 校验）。
 *
 * <p>对应 fuyun.trace.* 配置组（业务配置统一 fuyun.* 前缀，backend 宪法 A.2-4）；
 * {@code @Validated} 使约束在 Bean 绑定期即校验——配置缺值/空白在启动时 fail-fast，
 * 而非带病运行到第一次请求才暴露（backend 宪法 A.2-2）。禁止为业务配置散落 @Value。
 *
 * @param responseHeaderEnabled 是否回写 X-Trace-Id 响应头；来源：fuyun.trace.response-header-enabled，
 *                              供前端/网关以响应头关联日志排障
 * @param mdcKey                MDC 键名，非空白；来源：fuyun.trace.mdc-key（缺省 traceId），
 *                              须与 GlobalExceptionHandler 读取的键一致，改键将导致异常响应丢失追踪锚点
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.trace")
public record TraceProperties(
        boolean responseHeaderEnabled,
        @DefaultValue("traceId") @NotBlank String mdcKey) {}
