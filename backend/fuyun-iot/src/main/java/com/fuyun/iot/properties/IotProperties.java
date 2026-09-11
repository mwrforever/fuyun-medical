package com.fuyun.iot.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * IoT 接入配置属性（fuyun.iot.* 前缀，backend 宪法 A.2-2/A.2-4）。
 *
 * <p>record 构造器绑定 + 启动期校验。启用校验机制定稿（BRIEF-PR4-01 §3 properties 行二选一）：
 * <b>JSR-303 分组校验 + 属性类内校验方法</b>——Default 组约束（batchSize 区间等）由 @Validated 在
 * 绑定期自动生效；启用组 {@link Amqp.AmqpEnabled} 约束 endpoint/accessKey/accessSecret/queues 非空，
 * 由 AMQP 装配方（IotAmqpConfig）在 enabled=true 建 Bean 前调用
 * {@link Amqp#validateAmqpEnabled()} 激活 fail-fast。为何不用裸 @PostConstruct：enabled 默认 false
 * （application.yml 安全默认，宪法 B.4-4"网关不可用不得阻塞应用启动"），enabled=false 时
 * endpoint 等空值必须合法放行——绑定生命周期钩子无法区分启用与否，方向必反；分组校验把
 * "启用才必填"表达为约束本身，未启用零校验成本。
 *
 * <p>凭证红线：endpoint/accessKey/accessSecret 仅承 IOTDA_AMQP_* 环境变量注入，任何 profile
 * 禁明文默认值；校验失败消息只含字段路径不携带凭证值（BRIEF-PR4-01 §9-6）。
 */
@Validated
@ConfigurationProperties(prefix = "fuyun.iot")
public record IotProperties(
        @Valid @DefaultValue Amqp amqp, @Valid @DefaultValue Fallback fallback) {

    /**
     * AMQP 消费链配置（fuyun.iot.amqp.*，宪法 A.5-9 独立配置前缀与 Spring AMQP 完全隔离）。
     *
     * @param enabled              AMQP 消费链启用开关，默认 false（安全默认：IOTDA_* 缺失不阻塞
     *                             应用启动；true 时由 {@link #validateAmqpEnabled()} 承担缺失
     *                             fail-fast）；来源：fuyun.iot.amqp.enabled
     * @param endpoint             IoTDA AMQP 接入端点（本地 broker 为 amqp://host:5672，真实
     *                             IoTDA 为 amqps://host:5671）；仅启用组必填；来源：
     *                             IOTDA_AMQP_ENDPOINT 环境变量映射
     * @param accessKey            接入凭证 accessKey（IoTDA 建链 username 明文侧）；仅启用组必填；
     *                             来源：IOTDA_AMQP_ACCESS_KEY 环境变量映射；禁入日志
     * @param accessSecret         接入凭证 accessCode/secret（password = 值 + 13 位毫秒时间戳拼接）；
     *                             仅启用组必填；来源：IOTDA_AMQP_SECRET 环境变量映射；禁入日志
     * @param queues               订阅队列清单（IoTDA 规则引擎转发队列名），至少 1 条；仅启用组必填；
     *                             来源：fuyun.iot.amqp.queues[n]
     * @param queuePrefetch        单消费者预取上限，默认 1000（IoTDA 默认值，14-iot 调研依据 2）
     * @param batchSize            攒批落库条数阈值，默认 500，合法区间 500–5000（宪法 A.4.2-7
     *                             "遥测批量落库按 500-5000 条/批独立事务"）
     * @param batchFlushInterval   攒批时间窗触发间隔，默认 2s（距上次落库超时即刷批）
     * @param batchQueueCapacity   攒批有界内存队列容量，默认 5000（满则消费侧等待背压，
     *                             prefetch 自然限流）
     * @param reconnectInitialDelay 断链重连初始退避，默认 3s（宪法 A.5-9 failover 参数原文值）
     * @param reconnectMaxDelay    断链重连最大退避，默认 30s（指数退避上限，宪法 A.5-9 原文值）
     */
    public record Amqp(
            @DefaultValue("false") boolean enabled,

            @NotBlank(message = "endpoint 缺失（启用 AMQP 消费时必填）", groups = AmqpEnabled.class)
            String endpoint,

            @NotBlank(message = "accessKey 缺失（启用 AMQP 消费时必填）", groups = AmqpEnabled.class)
            String accessKey,

            @NotBlank(message = "accessSecret 缺失（启用 AMQP 消费时必填）", groups = AmqpEnabled.class)
            String accessSecret,

            @NotEmpty(message = "订阅队列清单为空（启用 AMQP 消费时至少配置 1 条队列）", groups = AmqpEnabled.class)
            List<String> queues,

            @Min(value = 1, message = "queuePrefetch 必须为正整数") @DefaultValue("1000")
            int queuePrefetch,

            @Min(value = 500, message = "batchSize 低于下界 500")
            @Max(value = 5000, message = "batchSize 超过上界 5000")
            @DefaultValue("500")
            int batchSize,

            @DurationMin(nanos = 1, message = "batchFlushInterval 必须为正") @DefaultValue("2s")
            Duration batchFlushInterval,

            @Min(value = 1, message = "batchQueueCapacity 必须为正整数") @DefaultValue("5000")
            int batchQueueCapacity,

            @DurationMin(nanos = 1, message = "reconnectInitialDelay 必须为正") @DefaultValue("3s")
            Duration reconnectInitialDelay,

            @DurationMin(nanos = 1, message = "reconnectMaxDelay 必须为正") @DefaultValue("30s")
            Duration reconnectMaxDelay) {

        /**
         * 启用组标记：仅承载"启用 AMQP 时才生效"的约束（endpoint/accessKey/accessSecret/queues
         * 非空），绑定期 Default 组校验不触碰本组，由 {@link #validateAmqpEnabled()} 显式激活。
         */
        public interface AmqpEnabled {}

        /** 启用组校验器：类级单例（ValidatorFactory 构建开销大，随 JVM 生命周期复用） */
        private static final Validator AMQP_ENABLED_VALIDATOR =
                Validation.buildDefaultValidatorFactory().getValidator();

        /**
         * 启用态连接参数 fail-fast 校验：enabled=true 时按启用组校验连接四要素并对队列清单做空段
         * 防御，任一缺失或无效即抛出阻断 AMQP Bean 装配（宪法 B.4-4：fail-fast 仅限显式启用场景）；
         * enabled=false 直接放行（空值合法的安全默认姿态）。由 AMQP 装配方在建 Bean 前调用。
         *
         * @throws IllegalStateException enabled=true 且 endpoint/accessKey/accessSecret/queues
         *                               任一缺失或空值，或 queues 含空白队列名（空段 env 元素）；
         *                               消息只含字段路径与约束描述，不携带凭证值
         */
        public void validateAmqpEnabled() {
            if (!enabled) {
                return;
            }
            Set<ConstraintViolation<Amqp>> violations = AMQP_ENABLED_VALIDATOR.validate(this, AmqpEnabled.class);
            if (!violations.isEmpty()) {
                String detail = violations.stream()
                        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                        .collect(Collectors.joining("；"));
                throw new IllegalStateException("fuyun.iot.amqp.enabled=true 但 AMQP 连接参数缺失或不合法：" + detail);
            }
            // 空段队列名防御（2026-09-11 终审修复）：relaxed binding 对逗号分隔清单保留空段/空白项
            // （实测 "q1,,q2" 绑定为含空串元素列表），@NotEmpty 只拦整体缺失——显式拒绝无效元素，
            // 防消费者以空地址建链产生无意义连接
            if (queues != null && queues.stream().anyMatch(queue -> queue == null || queue.isBlank())) {
                throw new IllegalStateException(
                        "fuyun.iot.amqp.queues 含空白队列名（FUYUN_IOT_AMQP_QUEUES 为逗号分隔清单，" + "禁止空段与空白项，请与 IoTDA 推送队列逐一对齐）");
            }
        }
    }

    /**
     * HTTP 兜底通道配置（fuyun.iot.fallback.*，B4.3 兜底端点独立鉴权使用）。
     *
     * @param token 兜底通道共享密钥，与请求头 X-Iot-Fallback-Token 常量时间比对；未配置绑定为
     *              null（yml 空占位解析为空串，两态同义=未配置，比对侧 fail-closed 一律拒绝）；
     *              来源：FUYUN_IOT_FALLBACK_TOKEN 环境变量映射；禁入日志
     */
    public record Fallback(String token) {}
}
