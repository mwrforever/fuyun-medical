package com.fuyun.nursing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fuyun.nursing.internal.NursingConnectAuthInterceptor;
import com.fuyun.system.api.TokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * /ws/nursing STOMP 装配契约测试（Task 11 brief Step 2——端点路径/主题前缀/拦截器挂载断言）：
 * 端点恰为 /ws/nursing（根定位层 §7 统一 /ws/** 前缀、无 SockJS——bigscreen 原生 WebSocket）；
 * SimpleBroker 恰为 /topic 前缀（/topic/nursing/board/{wardId} 推送承载，与 iot/outpatient
 * 同值幂等）；clientInboundChannel 恰挂 CONNECT 帧鉴权拦截器；类级 @Configuration +
 * @EnableWebSocketMessageBroker 双注解在位（三 configurer 共存去重的装配前提）。
 */
class NursingWebSocketConfigTest {

    /** 受测配置（鉴权拦截器以真实构造挂载——TokenVerifier 桩注入） */
    private NursingWebSocketConfig config;

    @BeforeEach
    void setUp() {
        config = new NursingWebSocketConfig(new NursingConnectAuthInterceptor(mock(TokenVerifier.class)));
    }

    @Test
    @DisplayName("端点契约：恰注册 /ws/nursing 一个端点（纯 WebSocket 无 SockJS 分支）")
    void registersNursingEndpointOnly() {
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);

        config.registerStompEndpoints(registry);

        // 端点路径冻结断言：唯一端点 /ws/nursing（brief 冻结；SockJS 无 fallback——addEndpoint 后不再链式配置）
        verify(registry).addEndpoint("/ws/nursing");
    }

    @Test
    @DisplayName("主题契约：SimpleBroker 恰启用 /topic 前缀（board 推送唯一前缀）")
    void enablesTopicPrefixSimpleBroker() {
        MessageBrokerRegistry brokerRegistry = mock(MessageBrokerRegistry.class);

        config.configureMessageBroker(brokerRegistry);

        verify(brokerRegistry).enableSimpleBroker("/topic");
    }

    @Test
    @DisplayName("鉴权挂载契约：clientInboundChannel 恰挂 CONNECT 帧鉴权拦截器（构造注入同实例）")
    void mountsConnectAuthInterceptorOnInboundChannel() {
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(any(NursingConnectAuthInterceptor.class));
    }

    @Test
    @DisplayName("装配注解契约：@Configuration + @EnableWebSocketMessageBroker 双注解在位且实现 configurer 接口")
    void carriesConfigurationAnnotations() {
        assertThat(NursingWebSocketConfig.class.isAnnotationPresent(Configuration.class))
                .as("@Configuration 在位（app 侧 @Import 装配形态前提）")
                .isTrue();
        assertThat(NursingWebSocketConfig.class.isAnnotationPresent(EnableWebSocketMessageBroker.class))
                .as("@EnableWebSocketMessageBroker 在位（三 configurer 共存去重——同注解幂等）")
                .isTrue();
        assertThat(WebSocketMessageBrokerConfigurer.class)
                .as("实现 WebSocketMessageBrokerConfigurer（Delegating 收集装配前提）")
                .isAssignableFrom(NursingWebSocketConfig.class);
    }
}
