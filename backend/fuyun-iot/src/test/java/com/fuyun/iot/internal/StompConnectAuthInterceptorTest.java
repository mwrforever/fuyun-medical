package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.api.TokenPrincipal;
import com.fuyun.system.api.TokenVerifier;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * STOMP CONNECT 帧级鉴权拦截器单元测试（PR-5 Finding 1：鉴权点由握手层迁移至 CONNECT 帧，
 * 承接原 StompHandshakeAuthInterceptorTest 用例语义；PR-4D Task 7 适配携主体缓存语义，D-21
 * 申报——镜像 nursing 侧 NursingConnectAuthInterceptorTest 同款适配，布尔断言升级为主体断言
 * 且新增主体缓存断言，严格度提升）。
 *
 * <p>覆盖：合法 Bearer 令牌放行并缓存 TokenPrincipal 进会话属性（ATTR_TOKEN_PRINCIPAL——
 * SUBSCRIBE 限订防线消费源）、令牌校验未过（verifyAccessPrincipal 返 null）抛
 * MessagingException 拒绝（异常消息不含令牌值——该消息将进 ERROR 帧返回客户端，红线 6）、
 * Authorization 原生头缺失/非 Bearer 方案/Bearer 空白三态拒绝（提取 null 入校验契约，契约内
 * 归一返 null）、非 CONNECT 帧（SUBSCRIBE 等）直通不鉴权。真实容器 CONNECT 拒绝链路
 * （ERROR 帧 + PROTOCOL_ERROR 关闭）归 IotTelemetryPipelineIT 步骤 8。
 */
@ExtendWith(MockitoExtension.class)
class StompConnectAuthInterceptorTest {

    /** 测试令牌样本（无意义假值，仅具单测意义；真实令牌经 M01 登录或 bigscreen-token 签发） */
    private static final String VALID_TOKEN = "it-fake-access-token";

    @Mock
    private TokenVerifier tokenVerifier;

    @Mock
    private MessageChannel channel;

    private StompConnectAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompConnectAuthInterceptor(tokenVerifier);
    }

    @Test
    @DisplayName("合法 Bearer 令牌的 CONNECT 帧：校验通过后帧原样放行且主体缓存进会话属性")
    void allowsConnectAndCachesPrincipalWhenBearerTokenVerifies() {
        TokenPrincipal principal = new TokenPrincipal(1L, "admin", null);
        when(tokenVerifier.verifyAccessPrincipal(VALID_TOKEN)).thenReturn(principal);
        Map<String, Object> attrs = new HashMap<>();
        Message<byte[]> connectFrame = connectFrame("Bearer " + VALID_TOKEN, attrs);

        Message<?> result = interceptor.preSend(connectFrame, channel);

        assertThat(result).as("校验通过帧原样放行").isSameAs(connectFrame);
        // 携主体缓存语义（PR-4D Task 7）：主体写入会话属性（连接级），SUBSCRIBE 限订防线经此取哨兵锚
        assertThat(attrs.get(StompConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL))
                .as("CONNECT 通过即缓存令牌主体进会话属性")
                .isSameAs(principal);
    }

    @Test
    @DisplayName("令牌校验未通过（verifyAccessPrincipal 返 null）：抛 MessagingException 拒绝 CONNECT（异常消息不含令牌值防泄露）")
    void rejectsConnectWithMessagingExceptionWhenTokenFailsVerification() {
        when(tokenVerifier.verifyAccessPrincipal(VALID_TOKEN)).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer " + VALID_TOKEN, new HashMap<>()), channel))
                .as("校验未过必须拒绝 CONNECT（服务端将以 ERROR 帧 + PROTOCOL_ERROR 关闭连接）")
                .isInstanceOf(MessagingException.class)
                .hasMessageNotContaining(VALID_TOKEN);
    }

    @Test
    @DisplayName("Authorization 原生头缺失：拒绝（提取 null 入校验契约，契约内归一返 null）")
    void rejectsConnectWithoutAuthorizationHeaderBeforeVerification() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame(null, new HashMap<>()), channel))
                .as("头缺失视同拒绝")
                .isInstanceOf(MessagingException.class);
        // 三态在拦截器内合并：缺失头提取 null 后仍进校验契约（null 恒返 null，非二次校验）
        verify(tokenVerifier).verifyAccessPrincipal(null);
    }

    @Test
    @DisplayName("非 Bearer 方案（如 Basic）：拒绝（提取 null，同头缺失口径）")
    void rejectsConnectWithNonBearerScheme() {
        assertThatThrownBy(
                        () -> interceptor.preSend(connectFrame("Basic aXQtdXNlcjppdC1wYXNz", new HashMap<>()), channel))
                .as("非 Bearer 方案视同拒绝")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier).verifyAccessPrincipal(null);
    }

    @Test
    @DisplayName("Bearer 后空白：视同令牌缺失，拒绝（提取 null，同头缺失口径）")
    void rejectsConnectWhenBearerValueIsBlank() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer ", new HashMap<>()), channel))
                .as("Bearer 空白视同缺失")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier).verifyAccessPrincipal(null);
    }

    @Test
    @DisplayName("非 CONNECT 帧（SUBSCRIBE 等）直通不鉴权（CONNECTED 前订阅不可达，无需重复校验）")
    void passesThroughNonConnectFramesWithoutVerification() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        Message<byte[]> subscribeFrame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(subscribeFrame, channel);

        assertThat(result).as("非 CONNECT 帧原样直通").isSameAs(subscribeFrame);
        verify(tokenVerifier, never()).verifyAccessPrincipal(anyString());
    }

    /**
     * 构造 CONNECT 帧消息（标准测试范式：StompHeaderAccessor 创建后取消息头组装 Message，
     * 保证 MessageHeaderAccessor.getAccessor 能从帧上还原 accessor；会话属性表模拟
     * StompSubProtocolHandler 建帧时注入的 WS 会话 attributes 原引用）。
     *
     * @param authorizationHeader Authorization 原生头值，可空模拟缺失
     * @param sessionAttributes    会话属性表（承载缓存主体断言），非空
     * @return CONNECT 帧消息，非空
     */
    private static Message<byte[]> connectFrame(String authorizationHeader, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        accessor.setSessionAttributes(sessionAttributes);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
