package com.fuyun.integration.properties;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 消息治理配置属性（fuyun.messaging.* 前缀，backend 宪法 A.2-2/A.2-4）。
 *
 * <p>record 构造器绑定 + 启动期校验：取值非法时应用启动即失败（fail-fast），禁止运行期静默容错。
 * 正值约束取 Hibernate Validator 专属 {@code @DurationMin}——jakarta {@code @Positive} 无 Duration
 * 内建校验器（HV000030，首次真实上下文启动即暴露），语义等价"必须为正"。
 *
 * @param idempotencyRedisTtl 幂等 Redis 前置键 TTL，默认 24h，必须为正；DB 唯一索引为最终兜底，
 *                            TTL 只需覆盖常态重复投递窗口（非幂等正确性依据）；来源：fuyun.messaging.idempotency-redis-ttl 配置
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.messaging")
public record MessagingProperties(
        @DurationMin(nanos = 1) @DefaultValue("24h") Duration idempotencyRedisTtl) {}
