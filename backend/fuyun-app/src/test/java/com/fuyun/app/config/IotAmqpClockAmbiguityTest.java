package com.fuyun.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.config.IotAmqpConfig;
import com.fuyun.iot.config.IotWebSocketConfig;
import com.fuyun.iot.internal.TelemetrySummaryAggregator;
import com.fuyun.iot.properties.IotProperties;
import com.fuyun.iot.service.IConsumeErrorLogService;
import com.fuyun.iot.service.IDeviceStatusService;
import com.fuyun.iot.service.ITelemetryIngestService;
import com.fuyun.system.api.TokenVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * AMQP 启用态 Clock 候选唯一性装配锚（Task 18 探针 D1 回归锚，2026-09-27）：真栈暴露的缺陷为
 * {@code fuyun.iot.amqp.enabled=true} 时 iotAmqpClock 与原 iotPushClock 双 Clock Bean 并存，
 * Spring Modulith 事件注册表工厂方法（EventPublicationAutoConfiguration#eventPublicationRegistry）
 * 经 {@code ObjectProvider<Clock>} 按类型无标识解析（库内注入点挂不上 @Qualifier）二义失败，
 * 经 EventOpsJob 依赖链阻断 backend 启动（全量门禁 IT 恰有 @Primary 测试时钟遮蔽，故 CI 绿而
 * 真栈红）。修复形态：全局 Clock Bean 仅保留条件装配的 iotAmqpClock 单候选，推送节流时钟改为
 * IotWebSocketConfig 装配点显式构造（BillingWebConfig 取价时钟同款先例）。
 *
 * <p>锚两面（ApplicationContextRunner 走真实条件评估与真实 Modulith 自动配置，无容器 CI 可跑）：
 * ①缺陷机理面——双无主 Clock Bean 候选下注册表 Bean 创建必二义失败且双 bean 名全量出现（防再次
 * 引入第二个全局 Clock Bean 的守卫）；②修复面——真实 IotAmqpConfig（enabled=true + 连接四要素）
 * 与真实 IotWebSocketConfig 同上下文装配成功，Clock 候选恰一（iotAmqpClock）且注册表解析成功、
 * 聚合器 Bean 在位（推送时钟语义改由装配点承载）。AMQP 消费面 start 全异步（宪法 B.4-4），
 * 端点指向本机未监听端口不阻塞上下文刷新，测试即真实 enabled=true 装配形态。
 */
class IotAmqpClockAmbiguityTest {

    /** Modulith 事件注册表自动配置（真实参与方，D1 实锚注入点所在）：仓储以 mock 供格（无 DB 场景） */
    private final EventPublicationRepository repository = Mockito.mock(EventPublicationRepository.class);

    @Test
    @DisplayName("缺陷机理锚：双无主 Clock Bean 候选下 Modulith 注册表按类型解析二义失败（双 bean 名出现）")
    void dualUnqualifiedClockBeansBreakRegistryResolution() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EventPublicationAutoConfiguration.class))
                .withBean(EventPublicationRepository.class, () -> repository)
                .withUserConfiguration(DualUnqualifiedClockConfig.class)
                .run(context -> {
                    // 与真栈 D1 同形：eventPublicationRegistry 工厂方法在上下文刷新期即解析 Clock，启动失败
                    assertThat(context).as("双 Clock 候选应令上下文启动失败").hasFailed();
                    assertThat(collectMessages(context.getStartupFailure()))
                            .as("二义报告应点名两个候选 bean 与注册表工厂方法")
                            .contains("iotAmqpClock", "iotPushClock", "eventPublicationRegistry");
                });
    }

    @Test
    @DisplayName("修复面锚：AMQP enabled=true 真实双配置类装配，Clock 候选恰一且注册表解析成功")
    void amqpEnabledContextResolvesSingleClockCandidate() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(EventPublicationAutoConfiguration.class))
                .withBean(EventPublicationRepository.class, () -> repository)
                .withPropertyValues(
                        "fuyun.iot.amqp.enabled=true",
                        "fuyun.iot.amqp.endpoint=amqp://localhost:5672",
                        "fuyun.iot.amqp.access-key=it-anchor-user",
                        "fuyun.iot.amqp.access-secret=it-anchor-secret",
                        "fuyun.iot.amqp.queues[0]=/queues/it.anchor")
                .withUserConfiguration(IotAmqpConfig.class, IotWebSocketConfig.class, AnchorCollaboratorsConfig.class)
                .run(context -> {
                    assertThat(context).as("AMQP enabled=true 上下文应成功启动（D1 修复后）").hasNotFailed();
                    // Clock 候选恰一：条件装配的 iotAmqpClock（推送时钟已改装配点显式构造，不入候选集）
                    assertThat(context.getBeansOfType(Clock.class))
                            .as("全局 Clock Bean 应仅剩单候选")
                            .hasSize(1);
                    assertThat(context.containsBean("iotAmqpClock"))
                            .as("AMQP 凭证时钟在位")
                            .isTrue();
                    // 注册表按类型解析成功（D1 缺陷在此断裂的同一注入点）
                    assertThat(context.getBean(EventPublicationRegistry.class))
                            .as("Modulith 事件注册表应成功装配")
                            .isNotNull();
                    // 推送节流面聚合器 Bean 在位（时钟语义改由装配点显式构造承载）
                    assertThat(context.getBean(TelemetrySummaryAggregator.class))
                            .as("遥测摘要聚合器应经 IotWebSocketConfig @Bean 在位")
                            .isNotNull();
                });
    }

    /** 汇总异常因果链全部消息（启动失败根因深嵌套，防漏报候选名） */
    private String collectMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cur = failure; cur != null && cur.getCause() != cur; cur = cur.getCause()) {
            messages.append(cur.getMessage()).append(' ');
        }
        return messages.toString();
    }

    /**
     * 缺陷机理复刻配置：两个无 primary 的具名 Clock Bean（与 D1 爆发时的生产候选集同名同形）。
     */
    @Configuration
    static class DualUnqualifiedClockConfig {

        @Bean
        Clock iotAmqpClock() {
            return Clock.systemUTC();
        }

        @Bean
        Clock iotPushClock() {
            return Clock.systemUTC();
        }
    }

    /**
     * 真实 AMQP 链协作者供格（无 DB/无 Micrometer 后端的最小 mock 面，测试资产与真实凭证无关）。
     */
    @Configuration
    @EnableConfigurationProperties(IotProperties.class)
    static class AnchorCollaboratorsConfig {

        @Bean
        ITelemetryIngestService telemetryIngestService() {
            return Mockito.mock(ITelemetryIngestService.class);
        }

        @Bean
        IConsumeErrorLogService consumeErrorLogService() {
            return Mockito.mock(IConsumeErrorLogService.class);
        }

        @Bean
        IDeviceStatusService deviceStatusService() {
            return Mockito.mock(IDeviceStatusService.class);
        }

        @Bean
        TokenVerifier tokenVerifier() {
            return Mockito.mock(TokenVerifier.class);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
