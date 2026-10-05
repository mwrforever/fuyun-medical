package com.fuyun.iot.config;

import com.fuyun.iot.internal.IotSubscribeInterceptor;
import com.fuyun.iot.internal.StompConnectAuthInterceptor;
import com.fuyun.iot.internal.TelemetrySummaryAggregator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * IoT STOMP 端点装配（B4.3，BRIEF-PR4-01 §1.4；PR-5 Finding 1 鉴权点迁移；PR-4D Task 7
 * W-90 订阅防线）：/ws/iot 纯 WebSocket 端点 + 内存 SimpleBroker 的集中配置点。
 *
 * <p>端点：{@code /ws/iot}（根定位层 §7 统一 /ws/** 前缀，nginx /ws/ 升级路由已就绪）。
 * 鉴权与订阅防线挂载于 clientInboundChannel 双拦截器序（PR-4D Task 7，W-90 WS 面）：
 * {@link StompConnectAuthInterceptor} 在前（M01 令牌 CONNECT 帧校验并缓存令牌主体进会话属性，
 * 拒绝语义与安全等价声明见其 javadoc）+ {@link IotSubscribeInterceptor} 在后（大屏哨兵
 * SUBSCRIBE 限订四主题白名单，登录态全放行，消费前者缓存的主体）——升级端点不再挂
 * HandshakeInterceptor：浏览器原生 WebSocket 无法携带自定义 HTTP 头，stompjs connectHeaders
 * 只进入建连后的 CONNECT 帧，
 * 握手层鉴权对浏览器客户端必然 401（原 StompHandshakeAuthInterceptor 已删除）。纯 WebSocket
 * 传输不做 SockJS fallback（P0 客户端仅 PR-5 bigscreen 原生 WebSocket，简报 §1.4）。
 * 消息代理：内存 SimpleBroker 订阅前缀 /topic（/topic/iot/telemetry/{wardId} 遥测摘要与
 * /topic/iot/device-status/{wardId} 设备状态，P0 直推）；多实例 broker relay 与订阅级数据范围
 * 校验属 P1（14-iot FU-M14-07，§0 负面清单）。
 *
 * <p>com.fuyun.iot 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app IotConfig @Import 生效（不放宽扫描，宪法 B.1/B.4-12）；SimpMessagingTemplate 等
 * 消息基础设施 Bean 由 @EnableWebSocketMessageBroker 派生装配，TelemetryPushServiceImpl 注入
 * 消费。@Import 引入帧级双拦截器（CONNECT 鉴权注入 TokenVerifier、SUBSCRIBE 限订防线无外部
 * 依赖，构造器注入宪法 A.1-7）。
 */
@Configuration
@EnableWebSocketMessageBroker
@Import({StompConnectAuthInterceptor.class, IotSubscribeInterceptor.class})
public class IotWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** CONNECT 帧鉴权拦截器：双拦截器序第一位（鉴权通过即缓存令牌主体进会话属性） */
    private final StompConnectAuthInterceptor connectAuthInterceptor;

    /** SUBSCRIBE 哨兵限订拦截器：双拦截器序第二位（消费 CONNECT 阶段缓存的主体限订白名单） */
    private final IotSubscribeInterceptor subscribeInterceptor;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param connectAuthInterceptor CONNECT 帧鉴权拦截器，非空；来源：本配置 @Import 构造器注入
     * @param subscribeInterceptor   SUBSCRIBE 哨兵限订拦截器，非空；来源：本配置 @Import 构造器注入
     */
    public IotWebSocketConfig(
            StompConnectAuthInterceptor connectAuthInterceptor, IotSubscribeInterceptor subscribeInterceptor) {
        this.connectAuthInterceptor = connectAuthInterceptor;
        this.subscribeInterceptor = subscribeInterceptor;
    }

    /** 注册 /ws/iot STOMP 端点（纯 WebSocket 无 SockJS；无握手层拦截器，鉴权见帧级拦截器）。 */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/iot");
    }

    /** 双拦截器序挂载 clientInboundChannel：CONNECT 鉴权在前（注入会话主体），SUBSCRIBE 哨兵限订在后（消费主体）——W-90 WS 面。 */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(connectAuthInterceptor, subscribeInterceptor);
    }

    /** 内存 SimpleBroker 承载 /topic/** 订阅（P0 直推；应用前缀未配置——推送仅服务端发起）。 */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    /**
     * 遥测摘要 2s 窗口聚合器 Bean（Task 18 探针 D1 修复，2026-09-27）：推送节流面时钟在装配点
     * 显式构造注入 {@code Clock.systemUTC()}（BillingWebConfig 取价时钟同款先例）——<b>不注册全局
     * Clock Bean</b>。原形态 {@code @Bean("iotPushClock")} 与 IotAmqpConfig 条件装配的 iotAmqpClock
     * （fuyun.iot.amqp.enabled=true 时存在）同类型并存，Spring Modulith 事件注册表工厂方法按类型
     * 无标识解析 Clock（{@code ObjectProvider<Clock>} 注入，库内注入点挂不上 @Qualifier）即二义
     * 失败阻断启动（EventOpsJob 依赖链实锚）；命名不豁免候选集——删除后 enabled 两态下按类型
     * 候选均 ≤1（true=iotAmqpClock 单候选，false=零候选走注册表内置 UTC 默认），AMQP 凭证时钟
     * 与推送节流时钟语义各自不变（推送时钟生产恒为系统 UTC，单测经构造器注入固定时钟）。
     *
     * @return 窗口聚合器实例，singleton 无状态（时钟源只读）
     */
    @Bean
    public TelemetrySummaryAggregator telemetrySummaryAggregator() {
        return new TelemetrySummaryAggregator(Clock.systemUTC());
    }
}
