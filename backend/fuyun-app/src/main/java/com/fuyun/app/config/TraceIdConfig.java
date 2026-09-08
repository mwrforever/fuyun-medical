package com.fuyun.app.config;

import com.fuyun.app.properties.TraceProperties;
import com.fuyun.common.context.TraceIdFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * traceId 过滤器装配配置：示范 @ConfigurationProperties 启用与 Servlet 组件 Bean 的集中注册（backend 宪法 B.1）。
 *
 * <p>TraceIdFilter 本体无 {@code @Component}（公共模块不做装配假设），在此以
 * {@link FilterRegistrationBean} 注册：最高优先级保证 traceId 先于其他过滤器建立，
 * 全链路日志关联自请求最前端生效。构造器注入强制，禁止字段注入（backend 宪法 A.1-7）。
 */
@Configuration
@EnableConfigurationProperties(TraceProperties.class)
public class TraceIdConfig {

    /**
     * 注册 traceId 过滤器：覆盖全部路由，最高优先级执行。
     *
     * @param properties trace 配置，非空；来源：fuyun.trace.* 配置组经构造器绑定注入
     * @return 过滤器注册 Bean；已绑定 TraceIdFilter、/* 路由与最高优先级
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceProperties properties) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(
                new TraceIdFilter(properties.mdcKey(), properties.responseHeaderEnabled()));
        // traceId 必须先于所有其他过滤器存在，取最高优先级
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
