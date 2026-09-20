package com.fuyun.outpatient.config;

import com.fuyun.outpatient.internal.OutpatientConnectAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 门诊 STOMP 端点装配（裁决 12——outpatient 自建 WS 面，禁依赖 fuyun-iot）：/ws/outpatient 纯
 * WebSocket 端点 + 内存 SimpleBroker 的集中配置点。
 *
 * <p>端点：{@code /ws/outpatient}（根定位层 §7 统一 /ws/** 前缀，nginx /ws/ 升级路由已就绪）；
 * 通道=目的地 {@code /topic/outpatient/queue/{deptCode}}（大屏/语音）与
 * {@code /topic/outpatient/doctor/{doctorId}}（医生站提醒），Spec :179 的
 * {@code /ws/outpatient/queue/{queueId}} 表述按「端点/目的地」两段式映射（queueId=dept_code，
 * 偏差⑧）。鉴权挂载于 clientInboundChannel 的 {@link OutpatientConnectAuthInterceptor}
 * （M01 令牌 CONNECT 帧校验，镜像 iot 侧实现——偏差⑥，收敛锚 TASK.md W-25）。纯 WebSocket
 * 传输不做 SockJS fallback（P0 客户端仅 bigscreen 原生 WebSocket）。
 *
 * <p>双 configurer 共存语义：fuyun-app 上下文同时装配 IotWebSocketConfig 与本类——
 * {@code @EnableWebSocketMessageBroker} 重复导入为 Spring 去重 no-op（同注解幂等）；
 * DelegatingWebSocketMessageBrokerConfiguration 收集全部 WebSocketMessageBrokerConfigurer，
 * 各 registerStompEndpoints 叠加生效（双端点并存），configureMessageBroker 对 /topic 前缀
 * 同值幂等（enableSimpleBroker 重复注册同前缀无害）。消息基础设施 Bean（SimpMessagingTemplate
 * 等）由 @EnableWebSocketMessageBroker 派生装配，TriageServiceImpl 注入消费。
 *
 * <p>com.fuyun.outpatient 包不在 @SpringBootApplication 扫描范围（com.fuyun.app.*）内，本配置
 * 经 fuyun-app OutpatientConfig @Import 生效（不放宽扫描，宪法 B.1）；@Import 引入帧级鉴权
 * 拦截器（构造器注入 TokenVerifier，宪法 A.1-7）。
 */
@Configuration
@EnableWebSocketMessageBroker
@Import(OutpatientConnectAuthInterceptor.class)
public class OutpatientWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** CONNECT 帧鉴权拦截器：挂载于 clientInboundChannel（未授权 CONNECT 被拒，无数据暴露） */
    private final OutpatientConnectAuthInterceptor connectAuthInterceptor;

    /**
     * 全参构造器（装配归 fuyun-app OutpatientConfig @Import，backend 宪法 B.1）。
     *
     * @param connectAuthInterceptor CONNECT 帧鉴权拦截器，非空；来源：本配置 @Import 构造器注入
     */
    public OutpatientWebSocketConfig(OutpatientConnectAuthInterceptor connectAuthInterceptor) {
        this.connectAuthInterceptor = connectAuthInterceptor;
    }

    /** 注册 /ws/outpatient STOMP 端点（纯 WebSocket 无 SockJS；无握手层拦截器，鉴权见帧级拦截器）。 */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/outpatient");
    }

    /** CONNECT 帧级鉴权拦截器挂载 clientInboundChannel（与 iot 侧同构，镜像语义见拦截器 javadoc）。 */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(connectAuthInterceptor);
    }

    /** 内存 SimpleBroker 承载 /topic/** 订阅（与 iot 侧同值幂等——双 configurer 共存去重依据）。 */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }
}
