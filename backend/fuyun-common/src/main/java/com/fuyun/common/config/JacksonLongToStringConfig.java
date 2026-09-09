package com.fuyun.common.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson Long→String 全局序列化配置：JSON 序列化精度防线的全项目唯一注册点。
 *
 * <p>backend 宪法 A.3-8：雪花 ID、金额分值等超出 JS {@code Number.MAX_SAFE_INTEGER}（2^53）的
 * 长整型统一以字符串输出，前端以字符串接收，杜绝精度丢失；禁止各接口零散注册序列化定制。
 * 同时注册包装 {@code Long} 与原生 {@code long} 两条序列化路径，覆盖实体 ID（包装）与
 * 金额/计数字段（原生）两类来源。
 *
 * <p>装配归 fuyun-app（@Import 本类，宪法 B.1）：Boot 自动装配的 ObjectMapper 与 Spring MVC
 * 共用同一实例，REST 与消息（Jackson2JsonMessageConverter 同源构建）双侧同时生效。
 */
@Configuration
public class JacksonLongToStringConfig {

    /**
     * 注册 Long/long → String 序列化定制器。
     *
     * @return 定制器；由 Boot 自动装配消费，作用于全局 ObjectMapper
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
