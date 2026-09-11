package com.fuyun.iot.config;

import com.fuyun.iot.internal.IotAmqpMetrics;
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
 * <p><b>连接 URI（宪法 A.5-9 三参数 + B4.4 实测修正选项语法与移交机制）</b>：{@code failover:(amqp://...)?
 * failover.initialReconnectDelay=3000&failover.reconnectDelay=3000&failover.maxReconnectDelay=30000
 * &failover.maxReconnectAttempts=3}——P0 对接本地 broker 为 {@code amqp://}，生产 IoTDA 真实端点为
 * {@code amqps://host:5671}（TLS 1.2+，14-iot 调研依据 1，仅 env 注入的 endpoint 值差异，代码零改动）；
 * transport 级 failover 透明重连不刷新凭证内嵌时间戳（IoTDA 拒绝超 5 分钟旧时间戳），且其官方语义为
 * 纯透明恢复（B4.4 本地实测：不触发 ExceptionListener、阻塞中的 receive() 持续等待重连）——无限透明
 * 重试将令 supervisor 永不介入，故 maxReconnectAttempts 设有限值 3：provider 放弃后连接失败，控制权
 * 移交 supervisor 以新时间戳凭证无限重建（重连无限语义上移到凭证刷新层，2026-09-11 CHANGELOG 登记）；
 * 三延迟参数按官方「failover.」前缀语法补正（B4.2 起的裸名形态不被 failover 层识别，取值不变）。
 * queuePrefetch=1000 为 IoTDA 默认（14-iot 调研依据 2）。
 *
 * <p><b>连接数预算（宪法 A.5-9）</b>：连接数 = 实例数 × 每实例连接数（每实例连接数 = 配置队列数，
 * 每队列独占一条连接）≤ 32（IoTDA 单凭证上限）；P0 单实例 × ≤4 队列 = ≤4 连接，扩容上界
 * 8 实例 × 4 队列 = 32。
 *
 * <p>归 fuyun-iot config/ 包（宪法 B.1 配置集中），Bean 注册点为 fuyun-app IotConfig @Import
 * （iot 包不在组件扫描范围）；@Import 引入攒批器、消费者与双指标绑定三个 Bean（phase=0/1
 * 保证"先攒批接帧后拉取"启动，停止按 phase 降序反向——"先停拉取再排空在途批"，宪法 A.5-15）。
 */
@Configuration
@ConditionalOnProperty(name = "fuyun.iot.amqp.enabled", havingValue = "true")
@Import({TelemetryBatchAssembler.class, IotAmqpTelemetryConsumer.class, IotAmqpMetrics.class})
public class IotAmqpConfig {

    /**
     * 传输层透明重连的有限尝试上限（次）：连续失败达到该次数后 provider 放弃并使连接失败
     * （ExceptionListener 触发 / 阻塞中的 receive 失败上抛），控制权移交消费者 supervisor——
     * 由 supervisor 以新时间戳凭证无限重建（「无限重连」语义上移到凭证刷新层）。
     *
     * <p>为何必须有限（B4.4 IotAmqpReconnectIT 实测发现，T-R3-3 本地实测结论）：① Qpid failover
     * 对断链做纯透明恢复——ExceptionListener 不触发、receive() 持续阻塞等待重连，无限重试
     * （maxReconnectAttempts=-1）下 supervisor 永不介入；生产推演下 IoTDA 断链超 5 分钟后
     * failover 仍以连接建立时捕获的旧时间戳凭证永续重试，被服务端拒绝后消费链路无感知——
     * supervisor 的「新时间戳凭证重建」被完全屏蔽。② qpid-jms 官方选项表无 timeout 类移交参数
     * （failover.timeout 属 ActiveMQ failover 词表，Qpid 2.11 装配即报 provider 创建失败，实测留证），
     * 有限尝试是官方选项表内唯一的移交机制。3 次取值兼顾「瞬时抖动（≤3 次重试约 9s 内）仍走透明
     * 恢复」与「断链感知时延约 3s×3 次 + 3s supervisor 退避」；检测后重连无限次由 supervisor 承接，
     * 总重连次数不设上限。
     */
    static final int FAILOVER_MAX_RECONNECT_ATTEMPTS = 3;

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
        // failover 选项一律用官方「failover.」前缀形态（qpid-jms 官方文档语法；B4.4 实测后修正——
        // B4.2 起的裸名 initialReconnectDelay 等不会被 failover 层识别为选项，语义等同未配置）；
        // maxReconnectAttempts 由 -1 改为有限值移交 supervisor（详见 FAILOVER_MAX_RECONNECT_ATTEMPTS 注释）
        String remoteUri = "failover:(" + properties.amqp().endpoint() + ")"
                + "?failover.initialReconnectDelay=" + initialDelayMillis
                + "&failover.reconnectDelay=" + initialDelayMillis
                + "&failover.maxReconnectDelay="
                + properties.amqp().reconnectMaxDelay().toMillis()
                + "&failover.maxReconnectAttempts=" + FAILOVER_MAX_RECONNECT_ATTEMPTS;
        JmsConnectionFactory connectionFactory = new JmsConnectionFactory(remoteUri);
        // queuePrefetch 走类型安全的策略接口（endpoint 值可能自带查询参数，禁 URI 追加拼接）
        if (connectionFactory.getPrefetchPolicy() instanceof JmsDefaultPrefetchPolicy prefetchPolicy) {
            prefetchPolicy.setQueuePrefetch(properties.amqp().queuePrefetch());
        }
        return connectionFactory;
    }
}
