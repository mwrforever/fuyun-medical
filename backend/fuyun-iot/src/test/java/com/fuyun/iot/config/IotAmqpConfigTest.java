package com.fuyun.iot.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.iot.properties.IotProperties;
import java.time.Duration;
import java.util.List;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IoTDA AMQP 连接工厂装配单测（2026-09-12 凭证格式修复条目）：固化连接 URI 组装契约——生产
 * amqps（IoTDA）子 URI 携带官方三子参数（vhost=default / idleTimeout=8000 / saslMechanisms=PLAIN，
 * 来源《AMQP客户端接入说明》iot_01_00100_2）；本地联调 amqp:// RabbitMQ 不携带（其 vhost 为 "/"
 * 且非 IoTDA 端，追加 vhost=default 将无法建链）；failover 三延迟与有限重试参数（官方「failover.」
 * 前缀形态）两形态均原样保留。
 */
class IotAmqpConfigTest {

    /**
     * 以指定端点构造启用态 IotProperties（其余参数取生产同型值：prefetch 1000、批 500/2s/5000、
     * 退避 3s→30s，测试资产凭证与任何真实 IOTDA 凭证无关）。
     *
     * @param endpoint AMQP 接入端点（amqps:// 生产 IoTDA / amqp:// 本地 broker）
     * @return 完整配置属性，非空
     */
    private static IotProperties propsOf(String endpoint) {
        return new IotProperties(
                new IotProperties.Amqp(
                        true,
                        endpoint,
                        "test-access-key",
                        "test-access-secret",
                        List.of("it.iot.telemetry"),
                        1000,
                        500,
                        Duration.ofSeconds(2),
                        5000,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(30)),
                new IotProperties.Fallback(null));
    }

    @Test
    @DisplayName("生产 amqps 端点：子 URI 携带官方 vhost/idleTimeout/saslMechanisms 三子参数且 failover 参数保留")
    void buildsAmqpsUriWithOfficialIotdaChildOptions() {
        JmsConnectionFactory factory = new IotAmqpConfig().iotAmqpConnectionFactory(propsOf("amqps://iotda-host:5671"));

        assertThat(factory.getRemoteURI())
                .as("amqps 子 URI 追加官方三子参数（IoTDA 接入面），failover 选项保持官方前缀形态")
                .startsWith("failover:(amqps://iotda-host:5671"
                        + "?amqp.vhost=default&amqp.idleTimeout=8000&amqp.saslMechanisms=PLAIN)")
                .contains("?failover.initialReconnectDelay=3000")
                .contains("&failover.reconnectDelay=3000")
                .contains("&failover.maxReconnectDelay=30000")
                .contains("&failover.maxReconnectAttempts=3");
        assertThat(asQueuePrefetch(factory))
                .as("queuePrefetch 走策略接口而非 URI 拼接（IoTDA 默认 1000）")
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("本地 amqp:// 端点：不追加 IoTDA 子参数（vhost=default 对本地 RabbitMQ 不适用）且 failover 参数保留")
    void buildsAmqpUriWithoutIotdaChildOptions() {
        JmsConnectionFactory factory = new IotAmqpConfig().iotAmqpConnectionFactory(propsOf("amqp://127.0.0.1:5672"));

        assertThat(factory.getRemoteURI())
                .as("本地形态零 IoTDA 子参数，failover 三延迟与有限重试原样保留")
                .isEqualTo("failover:(amqp://127.0.0.1:5672)?failover.initialReconnectDelay=3000"
                        + "&failover.reconnectDelay=3000&failover.maxReconnectDelay=30000"
                        + "&failover.maxReconnectAttempts=3");
    }

    /** 读取工厂 queuePrefetch 策略值（JmsDefaultPrefetchPolicy 为 qpid 默认实现）。 */
    private static int asQueuePrefetch(JmsConnectionFactory factory) {
        if (factory.getPrefetchPolicy() instanceof org.apache.qpid.jms.policy.JmsDefaultPrefetchPolicy prefetchPolicy) {
            return prefetchPolicy.getQueuePrefetch();
        }
        throw new IllegalStateException("qpid 预取策略类型非预期（应为 JmsDefaultPrefetchPolicy）");
    }
}
