package com.fuyun.integration.properties;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 消息治理配置属性（fuyun.messaging.* 前缀，backend 宪法 A.2-2/A.2-4）。
 *
 * <p>record 构造器绑定 + 启动期校验：取值非法时应用启动即失败（fail-fast），禁止运行期静默容错。
 *
 * @param idempotencyRedisTtl 幂等 Redis 前置键 TTL，默认 24h；DB 唯一索引为最终兜底，
 *                            TTL 只需覆盖常态重复投递窗口（非幂等正确性依据）；来源：fuyun.messaging.idempotency-redis-ttl 配置
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.messaging")
public record MessagingProperties(
        @DefaultValue("24h") @Positive Duration idempotencyRedisTtl) {}
