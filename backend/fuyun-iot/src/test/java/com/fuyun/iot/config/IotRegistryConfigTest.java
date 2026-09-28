package com.fuyun.iot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.iot.registry.IotDeviceRegistry;
import com.fuyun.iot.registry.huawei.HuaweiIotdaRegistry;
import com.fuyun.iot.registry.simulated.SimulatedRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 注册中心双条件装配单测（P2 PR-2 Task 4 Step 4，brief 红线断言面）：默认（fuyun.iot.admin.enabled
 * 缺席/false）下 Huawei bean 不装配且 Simulated 生效（CI 与单测一律走模拟实现）；enabled=true
 * 凭证齐全时 Huawei 装配且 Simulated 退位；enabled=true 凭证缺失时启动 fail-fast。经
 * ApplicationContextRunner 走真实条件评估与属性绑定（SecurityPropertiesTest 同款载体）。
 */
class IotRegistryConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IotRegistryConfig.class)
            // 生产环境由 Boot Jackson 自动配置提供 ObjectMapper；测试运行器直注册同型实例
            .withBean(ObjectMapper.class);

    @Test
    @DisplayName("默认（enabled 缺席）：Huawei bean 不装配，Simulated 生效（CI/单测安全默认）")
    void defaultsToSimulatedRegistry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(IotDeviceRegistry.class))
                    .as("默认装配面仅模拟实现单 bean")
                    .hasSize(1);
            assertThat(context.getBean(IotDeviceRegistry.class))
                    .as("默认实现为 SimulatedRegistry")
                    .isInstanceOf(SimulatedRegistry.class);
            assertThat(context.containsBean("huaweiIotdaRegistry"))
                    .as("enabled 缺席时 Huawei bean 不存在")
                    .isFalse();
        });
    }

    @Test
    @DisplayName("enabled=false 显式关闭：同默认面走 Simulated（显式声明与缺席同语义）")
    void explicitDisabledKeepsSimulatedRegistry() {
        runner.withPropertyValues("fuyun.iot.admin.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(IotDeviceRegistry.class)).isInstanceOf(SimulatedRegistry.class);
        });
    }

    @Test
    @DisplayName("enabled=true 凭证齐全：Huawei bean 装配且 Simulated 退位（互斥双条件）")
    void enabledWithCredentialsAssemblesHuaweiRegistry() {
        runner.withPropertyValues(
                        "fuyun.iot.admin.enabled=true",
                        "fuyun.iot.admin.endpoint=https://iotda-endpoint.example.com",
                        "fuyun.iot.admin.project-id=project-1",
                        "fuyun.iot.admin.access-key=test-ak",
                        "fuyun.iot.admin.access-secret=test-sk")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(IotDeviceRegistry.class))
                            .as("启用面仅华为实现单 bean")
                            .hasSize(1);
                    assertThat(context.getBean(IotDeviceRegistry.class))
                            .as("启用实现为 HuaweiIotdaRegistry（client 惰性构建，装配期零网络请求）")
                            .isInstanceOf(HuaweiIotdaRegistry.class);
                });
    }

    @Test
    @DisplayName("enabled=true 凭证缺失：validateAdminEnabled fail-fast 阻断启动且消息含字段路径")
    void enabledWithoutCredentialsFailsStartup() {
        runner.withPropertyValues("fuyun.iot.admin.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            // 取完整堆栈文本逐项断言（启动失败消息在 cause 链内，取文本自含断言）
            java.io.StringWriter trace = new java.io.StringWriter();
            context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
            assertThat(trace.toString())
                    .as("fail-fast 消息逐项指明缺失字段（不含凭证值——凭证本就缺失）")
                    .contains("fuyun.iot.admin.enabled=true", "endpoint", "projectId");
        });
    }
}
