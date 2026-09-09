package com.fuyun.app.config;

import com.fuyun.common.config.JacksonLongToStringConfig;
import com.fuyun.integration.config.MessagingGovernanceConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 消息与序列化公共装配：将 common / integration 配置类引入 Boot 上下文的集中入口（backend 宪法 B.1 装配归 app）。
 *
 * <p>common / integration 包均不在 @SpringBootApplication 默认扫描范围（com.fuyun.app.*）内，
 * 且均不提供自动装配清单，必须经此 @Import 显式引入；本类零业务逻辑（PR #4 既有裁决：不放宽扫描范围）。
 * Long→String 全局定制与消息治理构件（三交换机 + 死信统一队列 + 声明构件 + event_registry 服务）
 * 经此一并生效。
 */
@Import({JacksonLongToStringConfig.class, MessagingGovernanceConfig.class})
@Configuration
public class MessagingConfig {}
