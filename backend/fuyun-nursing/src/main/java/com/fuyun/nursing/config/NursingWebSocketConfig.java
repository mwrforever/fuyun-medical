package com.fuyun.nursing.config;

import com.fuyun.nursing.internal.NurseBoardPushListener;
import com.fuyun.nursing.internal.NursingConnectAuthInterceptor;
import com.fuyun.nursing.internal.NursingSubscribeWardInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 护理 STOMP 端点装配（FU-M05-08，Task 11——照抄 IotWebSocketConfig/OutpatientWebSocketConfig
 * 形态；PR-4C Task 7 增 SUBSCRIBE 病区防线）：/ws/nursing 纯 WebSocket 端点 + 内存 SimpleBroker
 * 的集中配置点。
 *
 * <p>端点：{@code /ws/nursing}（根定位层 §7 统一 /ws/** 前缀，nginx /ws/ 升级路由已就绪）。
 * 鉴权挂载于 clientInboundChannel 的双拦截器序（A-2）：{@link NursingConnectAuthInterceptor}
 * 在前（M01 令牌 CONNECT 帧校验——登录 access 与 bigscreen 匿名短期令牌同构两态通吃，通过即
 * 缓存令牌主体进会话属性）+ {@link NursingSubscribeWardInterceptor} 在后（board 主题族病区
 * 防线——哨兵单病区/登录态当班绑定集 fail-closed，消费前者缓存的主体）；升级端点不挂
 * HandshakeInterceptor（浏览器原生 WebSocket 无法携带自定义 HTTP 头，stompjs connectHeaders
 * 只进入建连后的 CONNECT 帧——iot PR-5 Finding 1 同源结论）。纯 WebSocket 传输不做 SockJS
 * fallback（客户端仅 bigscreen 原生 WebSocket）。
 *
 * <p>消息代理：内存 SimpleBroker 订阅前缀 /topic（/topic/nursing/board/{wardId} 大屏统一
 * 信封推送，NurseBoardPushListener 唯一出口）。推送时序=事务提交后 AFTER_COMMIT +
 * fallbackExecution（brief 冻结：事务内禁推送；MQ 消费零事务路径经 fallback 仍推）。
 *
 * <p>多 configurer 共存语义：fuyun-app 上下文同时装配 IotWebSocketConfig/
 * OutpatientWebSocketConfig 与本类——@EnableWebSocketMessageBroker 重复导入为 Spring 去重
 * no-op；DelegatingWebSocketMessageBrokerConfiguration 收集全部 configurer，各
 * registerStompEndpoints 叠加生效（三端点并存），configureMessageBroker 对 /topic 前缀同值
 * 幂等（OutpatientWebSocketConfig javadoc 双 configurer 论证的自然延伸）。消息基础设施 Bean
 * （SimpMessagingTemplate 等）由 @EnableWebSocketMessageBroker 派生装配，
 * NurseBoardPushListener 注入消费。
 *
 * <p>com.fuyun.nursing 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置经
 * fuyun-app NursingConfig @Import 生效（不放宽扫描，宪法 B.1/B.4-12）；@Import 引入帧级双
 * 拦截器（CONNECT 鉴权注入 TokenVerifier、SUBSCRIBE 防线注入 IWardAccessService，构造器注入
 * 宪法 A.1-7）与大屏推送监听器（WS 推送执行点）。
 */
@Configuration
@EnableWebSocketMessageBroker
@Import({NursingConnectAuthInterceptor.class, NursingSubscribeWardInterceptor.class, NurseBoardPushListener.class})
public class NursingWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** CONNECT 帧鉴权拦截器：双拦截器序第一位（鉴权通过即缓存令牌主体进会话属性） */
    private final NursingConnectAuthInterceptor connectAuthInterceptor;

    /** SUBSCRIBE 病区防线拦截器：双拦截器序第二位（消费 CONNECT 阶段缓存的主体限行 board 订阅） */
    private final NursingSubscribeWardInterceptor subscribeWardInterceptor;

    /**
     * 全参构造器（装配归 NursingConfig @Import，backend 宪法 B.1）。
     *
     * @param connectAuthInterceptor   CONNECT 帧鉴权拦截器，非空；来源：本配置 @Import 构造器注入
     * @param subscribeWardInterceptor SUBSCRIBE 病区防线拦截器，非空；来源：本配置 @Import 构造器注入
     */
    public NursingWebSocketConfig(
            NursingConnectAuthInterceptor connectAuthInterceptor,
            NursingSubscribeWardInterceptor subscribeWardInterceptor) {
        this.connectAuthInterceptor = connectAuthInterceptor;
        this.subscribeWardInterceptor = subscribeWardInterceptor;
    }

    /** 注册 /ws/nursing STOMP 端点（纯 WebSocket 无 SockJS；无握手层拦截器，鉴权见帧级拦截器）。 */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/nursing");
    }

    /** 双拦截器序挂载 clientInboundChannel：CONNECT 鉴权在前（注入会话主体），SUBSCRIBE 病区防线在后（消费主体）——A-2。 */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(connectAuthInterceptor, subscribeWardInterceptor);
    }

    /** 内存 SimpleBroker 承载 /topic/** 订阅（三 configurer 同值幂等——共存去重依据）。 */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }
}
