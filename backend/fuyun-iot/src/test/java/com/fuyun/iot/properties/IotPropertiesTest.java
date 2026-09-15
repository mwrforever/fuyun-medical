package com.fuyun.iot.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * IotProperties 启用校验机制测试（BRIEF-PR4-01 §3 properties 行：分组校验定稿的契约验证）。
 *
 * <p>两组契约分别验证：① Default 组（batchSize 500–5000 区间等）经 ApplicationContextRunner 走
 * 真实构造器绑定 + @Validated 校验链，绑定期越界即启动失败；② 启用组（AmqpEnabled）经
 * validateAmqpEnabled() 方法直测——enabled=false 时空值合法放行（安全默认姿态），enabled=true
 * 时连接四要素任一缺失即 fail-fast。测试凭证均为无意义空值/测试假值，与任何真实 IOTDA 凭证无关。
 */
class IotPropertiesTest {

    /** 启用态合法连接四要素（测试资产假值，仅具单测意义） */
    private static final IotProperties.Amqp ENABLED_AMQP = new IotProperties.Amqp(
            true,
            "amqp://127.0.0.1:5672",
            "test-access-key",
            "test-access-secret",
            List.of("it.iot.telemetry"),
            1000,
            500,
            Duration.ofSeconds(2),
            5000,
            Duration.ofSeconds(3),
            Duration.ofSeconds(30));

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindConfig.class);

    @Test
    @DisplayName("零配置绑定：enabled=false 安全默认生效，endpoint 等空值合法放行（不阻塞应用启动）")
    void bindsWithSafeDefaultsWhenNothingConfigured() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            IotProperties properties = context.getBean(IotProperties.class);
            // 安全默认：AMQP 关闭，连接参数缺失不构成启动失败（宪法 B.4-4 网关不可用不阻塞启动）
            assertThat(properties.amqp().enabled()).isFalse();
            assertThat(properties.amqp().endpoint()).isNull();
            assertThat(properties.amqp().queues()).isNull();
            // 区间/延迟类默认值（@DefaultValue 词表）
            assertThat(properties.amqp().queuePrefetch()).isEqualTo(1000);
            assertThat(properties.amqp().batchSize()).isEqualTo(500);
            assertThat(properties.amqp().batchFlushInterval()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.amqp().batchQueueCapacity()).isEqualTo(5000);
            assertThat(properties.amqp().reconnectInitialDelay()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.amqp().reconnectMaxDelay()).isEqualTo(Duration.ofSeconds(30));
            // 兜底通道默认未配置 token（null/空串=未配置，比对侧 fail-closed）
            assertThat(properties.fallback().token()).isNull();
        });
    }

    @ParameterizedTest(name = "batchSize={0}")
    @ValueSource(ints = {499, 5001})
    @DisplayName("batchSize 越界（低于 500 / 高于 5000）：Default 组绑定期拒绝，启动失败")
    void outOfRangeBatchSizeFailsStartup(int batchSize) {
        runner.withPropertyValues("fuyun.iot.amqp.batch-size=" + batchSize).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("batchSize");
        });
    }

    @Test
    @DisplayName("queuePrefetch 非正整数：Default 组绑定期拒绝，启动失败")
    void nonPositiveQueuePrefetchFailsStartup() {
        runner.withPropertyValues("fuyun.iot.amqp.queue-prefetch=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("queuePrefetch");
        });
    }

    @Test
    @DisplayName("enabled=false 时空值合法：validateAmqpEnabled 直接放行（空连接四要素不触发校验）")
    void disabledAmqpToleratesBlankConnectionParams() {
        IotProperties.Amqp disabled = new IotProperties.Amqp(
                false,
                "",
                "",
                "",
                null,
                1000,
                500,
                Duration.ofSeconds(2),
                5000,
                Duration.ofSeconds(3),
                Duration.ofSeconds(30));

        assertThatCode(disabled::validateAmqpEnabled).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enabled=true 且连接四要素齐全：validateAmqpEnabled 放行（AMQP Bean 可装配）")
    void enabledAmqpWithFullParamsPassesActivationValidation() {
        assertThatCode(ENABLED_AMQP::validateAmqpEnabled).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enabled=true 且 endpoint/accessKey/accessSecret/queues 缺失：validateAmqpEnabled fail-fast")
    void enabledAmqpWithMissingParamsFailsFast() {
        IotProperties.Amqp broken = new IotProperties.Amqp(
                true,
                "",
                "",
                "",
                null,
                1000,
                500,
                Duration.ofSeconds(2),
                5000,
                Duration.ofSeconds(3),
                Duration.ofSeconds(30));

        assertThatThrownBy(broken::validateAmqpEnabled)
                .isInstanceOf(IllegalStateException.class)
                // 消息点名全部缺失要素（含队列清单），供运维一次补齐；且不得携带凭证值
                .hasMessageContaining("endpoint")
                .hasMessageContaining("accessKey")
                .hasMessageContaining("accessSecret")
                .hasMessageContaining("queues");
    }

    @Test
    @DisplayName("enabled=true 且仅队列清单缺失：validateAmqpEnabled fail-fast 且消息只含字段路径不带凭证值")
    void enabledAmqpWithMissingQueuesOnlyFailsFast() {
        IotProperties.Amqp missingQueuesOnly = new IotProperties.Amqp(
                true,
                "amqp://127.0.0.1:5672",
                "test-access-key",
                "test-access-secret",
                null,
                1000,
                500,
                Duration.ofSeconds(2),
                5000,
                Duration.ofSeconds(3),
                Duration.ofSeconds(30));

        assertThatThrownBy(missingQueuesOnly::validateAmqpEnabled)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queues")
                // 敏感红线：校验消息不得携带凭证值（BRIEF-PR4-01 §9-6）
                .hasMessageNotContaining("test-access-secret");
    }

    @Test
    @DisplayName("queues 空串 env 绑定：relaxed binding 绑定为空列表（非 null、无空串元素），enabled=false 零校验通过")
    void emptyQueuesEnvBindsEmptyListAndSkipsValidationWhenDisabled() {
        runner.withPropertyValues("fuyun.iot.amqp.queues=").run(context -> {
            assertThat(context).hasNotFailed();
            IotProperties properties = context.getBean(IotProperties.class);
            assertThat(properties.amqp().enabled()).isFalse();
            // 绑定语义实测（2026-09-11 终审修复）：空串 env 经 relaxed binding 绑定为空列表，
            // 不产生含空串元素的列表——启用组 @NotEmpty 承接其 fail-fast（见下一用例）
            assertThat(properties.amqp().queues())
                    .as("空串 env 绑定为空列表")
                    .isNotNull()
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("queues 空串 env 且 enabled=true：空列表经启用组 @NotEmpty fail-fast（中文报错）")
    void enabledAmqpWithEmptyQueuesEnvFailsFastViaNotEmpty() {
        runner.withPropertyValues("fuyun.iot.amqp.enabled=true", "fuyun.iot.amqp.queues=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IotProperties properties = context.getBean(IotProperties.class);

                    assertThatThrownBy(properties.amqp()::validateAmqpEnabled)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("订阅队列清单为空");
                });
    }

    @Test
    @DisplayName("queues env 含空段/空白项且 enabled=true：validateAmqpEnabled 拒绝空白队列名（防订阅空地址）")
    void enabledAmqpWithBlankQueueElementsFailsFast() {
        runner.withPropertyValues(
                        "fuyun.iot.amqp.enabled=true",
                        "fuyun.iot.amqp.endpoint=amqp://127.0.0.1:5672",
                        "fuyun.iot.amqp.access-key=test-access-key",
                        "fuyun.iot.amqp.access-secret=test-access-secret",
                        // relaxed binding 对逗号分隔清单保留空段（实测 "q1,,q2" 绑定为 [q1, , q2]），
                        // @NotEmpty 只拦整体缺失，空段元素由 validateAmqpEnabled 显式拒绝
                        "fuyun.iot.amqp.queues=it.iot.telemetry,,second.queue")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IotProperties properties = context.getBean(IotProperties.class);

                    assertThatThrownBy(properties.amqp()::validateAmqpEnabled)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("空白队列名")
                            // 敏感红线：校验消息不得携带凭证值（BRIEF-PR4-01 §9-6）
                            .hasMessageNotContaining("test-access-secret");
                });
    }

    @Test
    @DisplayName("toString 脱敏：accessKey/accessSecret 打码不外泄，非敏感字段照常输出（W-5）")
    void amqpToStringMasksCredentials() {
        String text = ENABLED_AMQP.toString();

        assertThat(text).doesNotContain("test-access-key").doesNotContain("test-access-secret");
        assertThat(text).contains("accessKey=***", "accessSecret=***", "amqp://127.0.0.1:5672");
    }

    @Test
    @DisplayName("Fallback toString 脱敏：兜底通道共享密钥打码（W-5）")
    void fallbackToStringMasksToken() {
        String text = new IotProperties.Fallback("test-fallback-token").toString();

        assertThat(text).doesNotContain("test-fallback-token").contains("token=***");
    }

    /** 绑定载体：@EnableConfigurationProperties 生产同型注册（B4.2 任务 B IotConfig 承接） */
    @Configuration
    @Validated
    @EnableConfigurationProperties(IotProperties.class)
    static class BindConfig {}
}
