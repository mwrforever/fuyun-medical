package com.fuyun.app.config;

import com.fuyun.system.config.SystemWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M01 系统与权限管理模块装配：将 fuyun-system 配置类引入 Boot 上下文的集中入口
 * （backend 宪法 B.1 装配归 app，与 MessagingConfig/TraceIdConfig 同模式，不放宽组件扫描）。
 *
 * <p>认证链路（令牌服务/拦截器注册/认证端点 Bean）经 {@link SystemWebConfig} 一并生效；
 * 字典广播消息装配（SystemMessagingConfig）随字典域交付引入。
 */
@Import(SystemWebConfig.class)
@Configuration
public class SystemConfig {}
