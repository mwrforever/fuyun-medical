package com.fuyun.iot.config;

import com.fuyun.iot.internal.IotAmqpTelemetryConsumer;
import com.fuyun.iot.internal.TelemetryBatchAssembler;
import com.fuyun.iot.properties.IotProperties;
import java.time.Clock;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.qpid.jms.policy.JmsDefaultPrefetchPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * IoTDA AMQP 消费链装配（宪法 A.5-9：与 Spring AMQP 完全连接隔离的自建 Qpid 工厂 + 独立
 * {@code iot.amqp.*} 配置前缀 + 独立生命周期）。
 *
 * <p><b>启用开关（安全默认）</b>：{@code fuyun.iot.amqp.enabled=false} 时本配置类整体不生效，
 * 不建连接工厂与消费者 Bean，IOTDA_* 环境变量缺失不阻塞应用启动（宪法 B.4-4，fail-fast 仅限
 * DB/Redis/RabbitMQ）；enabled=true 时建 Bean 前先调 {@link IotProperties.Amqp#validateAmqpEnabled()}
 * 对连接四要素 fail-fast（缺失即启动失败）。
 *
 * <p><b>连接 URI（宪法 A.5-9 原文参数）</b>：{@code failover:(amqp://...)?initialReconnectDelay=3000
 * &reconnectDelay=3000&maxReconnectDelay=30000&failover.maxReconnectAttempts=-1}——P0 对接本地 broker
 * 为 {@code amqp://}，生产 IoTDA 真实端点为 {@code amqps://host:5671}（TLS 1.2+，14-iot 调研依据 1，
 * 仅 env 注入的 endpoint 值差异，代码零改动）；transport 级 failover 透明重连不刷新凭证内嵌时间戳
 * （IoTDA 拒绝超 5 分钟旧时间戳），故消费者侧 supervisor 始终销毁重建连接（IotAmqpTelemetryConsumer
 * 类注释），URI 层无限重连参数仅承载传输层兜底。queuePrefetch=1000 为 IoTDA 默认（14-iot 调研依据 2）。
 *
 * <p><b>连接数预算（宪法 A.5-9）</b>：连接数 = 实例数 × 每实例连接数（每实例连接数 = 配置队列数，
 * 每队列独占一条连接）≤ 32（IoTDA 单凭证上限）；P0 单实例 × ≤4 队列 = ≤4 连接，扩容上界
 * 8 实例 × 4 队列 = 32。
 *
 * <p>归 fuyun-iot config/ 包（宪法 B.1 配置集中），Bean 注册点为 fuyun-app IotConfig @Import
 * （iot 包不在组件扫描范围）；@Import 引入攒批器与消费者两个 SmartLifecycle Bean（phase=1/0
 * 保证"先攒批后消费启动、先停拉取再排空在途批"的停机顺序）。
 */
@Configuration
@ConditionalOnProperty(name = "fuyun.iot.amqp.enabled", havingValue = "true")
@Import({TelemetryBatchAssembler.class, IotAmqpTelemetryConsumer.class})
public class IotAmqpConfig {

    /**
     * AMQP 凭证时间戳时钟（IoTDA 口令 = accessSecret + 13 位毫秒时间戳，每次建链刷新）。
     *
     * <p>独立成 Bean 的原因：集成测试需注入固定时钟使口令可预置（IoTDA 语义的口令含时间戳后缀，
     * 本地 broker 只做字面比对）；生产恒为系统 UTC 时钟，消费链路禁止替换。
     *
     * @return 系统 UTC 时钟，非空
     */
    @Bean
    public Clock iotAmqpClock() {
        return Clock.systemUTC();
    }

    /**
     * IoTDA AMQP 连接工厂（Qpid JMS，jakarta.jms.ConnectionFactory 体系的独立实例，与 Spring AMQP
     * RabbitMQ ConnectionFactory 完全隔离）。
     *
     * <p>装配顺序契约：本 Bean 创建前先执行启用组校验——enabled=true 而 endpoint/accessKey/
     * accessSecret/queues 任一缺失即抛出阻断启动（fail-fast 前置于一切连接尝试）。failover 三参数
     * 取值链：initialReconnectDelay/reconnectDelay 取 reconnectInitialDelay（默认 3s）、
     * maxReconnectDelay 取 reconnectMaxDelay（默认 30s），与宪法 A.5-9 原文值一致；凭证不在工厂上
     * 预置（username/password 由消费者每次建链以新时间戳传入）。
     *
     * @param properties IoT 配置属性，非空；来源：fuyun-app IotConfig @EnableConfigurationProperties
     * @return Qpid JMS 连接工厂，非空
     * @throws IllegalStateException enabled=true 且连接四要素缺失（fail-fast，含字段路径不含凭证值）
     */
    @Bean
    public JmsConnectionFactory iotAmqpConnectionFactory(IotProperties properties) {
        properties.amqp().validateAmqpEnabled();
        long initialDelayMillis = properties.amqp().reconnectInitialDelay().toMillis();
        String remoteUri = "failover:(" + properties.amqp().endpoint() + ")"
                + "?initialReconnectDelay=" + initialDelayMillis
                + "&reconnectDelay=" + initialDelayMillis
                + "&maxReconnectDelay=" + properties.amqp().reconnectMaxDelay().toMillis()
                + "&failover.maxReconnectAttempts=-1";
        JmsConnectionFactory connectionFactory = new JmsConnectionFactory(remoteUri);
        // queuePrefetch 走类型安全的策略接口（endpoint 值可能自带查询参数，禁 URI 追加拼接）
        if (connectionFactory.getPrefetchPolicy() instanceof JmsDefaultPrefetchPolicy prefetchPolicy) {
            prefetchPolicy.setQueuePrefetch(properties.amqp().queuePrefetch());
        }
        return connectionFactory;
    }
}
