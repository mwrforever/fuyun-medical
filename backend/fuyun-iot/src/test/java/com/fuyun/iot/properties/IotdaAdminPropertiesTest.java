package com.fuyun.iot.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * IoTDA 管理面配置属性测试（P2 PR-2 Task 4 Step 2）：默认 enabled=false 空值合法（CI/单测
 * 安全默认）、启用组校验缺失凭证 fail-fast（IotProperties.Amqp.validateAmqpEnabled 同款形态）、
 * 凭证脱敏 toString（W-5 红线）。经 ApplicationContextRunner 走真实构造器绑定（SecurityPropertiesTest
 * 同款载体）。
 */
class IotdaAdminPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindConfig.class);

    @Test
    @DisplayName("默认绑定：enabled=false 且空值合法（未配 env 不阻塞启动的安全默认）")
    void defaultsBindWithDisabledState() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            IotdaAdminProperties properties = context.getBean(IotdaAdminProperties.class);
            assertThat(properties.enabled())
                    .as("默认关闭（CI/单测走 SimulatedRegistry）")
                    .isFalse();
            assertThat(properties.endpoint()).as("未启用时空值合法").isNull();
            assertThat(properties.accessSecret()).as("未启用时空值合法").isNull();
        });
    }

    @Test
    @DisplayName("enabled=true 但凭证缺失：validateAdminEnabled 抛 IllegalStateException 且消息含字段路径")
    void validateAdminEnabledFailsFastOnMissingCredentials() {
        IotdaAdminProperties properties = new IotdaAdminProperties(true, null, null, "only-ak", null);

        Throwable failure = catchThrowable(properties::validateAdminEnabled);

        assertThat(failure).as("启用组四要素任一缺失即 fail-fast（阻断 Huawei bean 装配）").isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage())
                .as("校验消息逐项指明缺失字段且不含凭证值")
                .contains("endpoint", "projectId", "accessSecret")
                .doesNotContain("only-ak");
    }

    @Test
    @DisplayName("enabled=true 凭证齐全：validateAdminEnabled 放行（零校验违例）")
    void validateAdminEnabledPassesWithFullCredentials() {
        IotdaAdminProperties properties = new IotdaAdminProperties(true, "https://endpoint", "project-1", "ak", "sk");

        assertThatCode(properties::validateAdminEnabled).as("启用态四要素齐全即放行").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("toString 脱敏：accessKey/accessSecret 打码不外泄，enabled/endpoint 照常输出（W-5）")
    void toStringMasksCredentials() {
        IotdaAdminProperties properties =
                new IotdaAdminProperties(true, "https://endpoint", "project-1", "plain-ak", "plain-sk");

        String text = properties.toString();

        assertThat(text).doesNotContain("plain-ak").doesNotContain("plain-sk");
        assertThat(text).contains("accessKey=***", "accessSecret=***", "enabled=true", "https://endpoint");
    }

    /** 绑定载体：@Validated 激活 JSR-303（生产经 IotRegistryConfig 同型注册） */
    @Configuration
    @Validated
    @org.springframework.boot.context.properties.EnableConfigurationProperties(IotdaAdminProperties.class)
    static class BindConfig {}
}
