package com.fuyun.app.config;

import com.fuyun.common.config.JacksonLongToStringConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 消息与序列化公共装配：将 common 包配置类引入 Boot 上下文的集中入口（backend 宪法 B.1 装配归 app）。
 *
 * <p>common 包不在 @SpringBootApplication 默认扫描范围（com.fuyun.app.*）内，且 common 不提供
 * 自动装配清单，必须经此 @Import 显式引入；本类零业务逻辑（PR #4 既有裁决：不放宽扫描范围）。
 * Long→String 全局定制经此生效后，Boot 的 ObjectMapper 与 Spring MVC 共用同一实例。
 */
@Import(JacksonLongToStringConfig.class)
@Configuration
public class MessagingConfig {}
