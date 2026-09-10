package com.fuyun.iot.config;

import com.fuyun.iot.internal.StompHandshakeAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * IoT STOMP 端点装配（B4.3，BRIEF-PR4-01 §1.4）：/ws/iot 纯 WebSocket 端点 + 内存 SimpleBroker
 * 的集中配置点。
 *
 * <p>端点：{@code /ws/iot}（根定位层 §7 统一 /ws/** 前缀，nginx /ws/ 升级路由已就绪）挂
 * {@link StompHandshakeAuthInterceptor} 握手鉴权（M01 令牌，失败 401 拒绝握手）；纯 WebSocket
 * 传输不做 SockJS fallback（P0 客户端仅 PR-5 bigscreen 原生 WebSocket，简报 §1.4）。
 * 消息代理：内存 SimpleBroker 订阅前缀 /topic（/topic/iot/telemetry/{wardId} 遥测摘要与
 * /topic/iot/device-status/{wardId} 设备状态，P0 直推）；多实例 broker relay 与订阅级数据范围
 * 校验属 P1（14-iot FU-M14-07，§0 负面清单）。
 *
 * <p>com.fuyun.iot 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app IotConfig @Import 生效（不放宽扫描，宪法 B.1/B.4-12）；SimpMessagingTemplate 等
 * 消息基础设施 Bean 由 @EnableWebSocketMessageBroker 派生装配，TelemetryPushServiceImpl 注入
 * 消费。@Import 引入握手拦截器（构造器注入 TokenVerifier，宪法 A.1-7）。
 */
@Configuration
@EnableWebSocketMessageBroker
@Import(StompHandshakeAuthInterceptor.class)
public class IotWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** 握手鉴权拦截器：端点注册时挂载（拒绝未认证 WebSocket 连接） */
    private final StompHandshakeAuthInterceptor handshakeAuthInterceptor;

    /**
     * 全参构造器（装配归 IotConfig @Import，backend 宪法 B.1）。
     *
     * @param handshakeAuthInterceptor 握手鉴权拦截器，非空；来源：本配置 @Import 构造器注入
     */
    public IotWebSocketConfig(StompHandshakeAuthInterceptor handshakeAuthInterceptor) {
        this.handshakeAuthInterceptor = handshakeAuthInterceptor;
    }

    /** 注册 /ws/iot STOMP 端点（纯 WebSocket 无 SockJS）并挂握手鉴权拦截器。 */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/iot").addInterceptors(handshakeAuthInterceptor);
    }

    /** 内存 SimpleBroker 承载 /topic/** 订阅（P0 直推；应用前缀未配置——推送仅服务端发起）。 */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }
}
