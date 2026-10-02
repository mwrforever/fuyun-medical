package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.system.api.TokenVerifier;
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
 * /ws/nursing CONNECT 帧鉴权拦截器单测（Task 11——iot StompConnectAuthInterceptorTest 同款
 * 用例组镜像）：合法 Bearer 令牌放行、校验未过抛 MessagingException（异常消息不含令牌值——
 * 进 ERROR 帧防泄露）、头缺失/非 Bearer/Bearer 空白三态拒绝且不触达校验器、非 CONNECT 帧
 * 直通不鉴权。
 */
@ExtendWith(MockitoExtension.class)
class NursingConnectAuthInterceptorTest {

    /** 测试令牌样本（无意义假值，仅具单测意义；真实令牌经 M01 登录或 bigscreen-token 签发） */
    private static final String VALID_TOKEN = "nursing-fake-access-token";

    @Mock
    private TokenVerifier tokenVerifier;

    @Mock
    private MessageChannel channel;

    private NursingConnectAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new NursingConnectAuthInterceptor(tokenVerifier);
    }

    @Test
    @DisplayName("合法 Bearer 令牌的 CONNECT 帧：TokenVerifier 校验通过后帧原样放行")
    void allowsConnectWhenBearerTokenVerifies() {
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(true);

        Message<byte[]> connectFrame = connectFrame("Bearer " + VALID_TOKEN);
        Message<?> result = interceptor.preSend(connectFrame, channel);

        assertThat(result).as("校验通过帧原样放行").isSameAs(connectFrame);
    }

    @Test
    @DisplayName("令牌校验未通过：抛 MessagingException 拒绝 CONNECT（异常消息不含令牌值防泄露）")
    void rejectsConnectWithMessagingExceptionWhenTokenFailsVerification() {
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer " + VALID_TOKEN), channel))
                .as("校验未过必须拒绝 CONNECT（服务端将以 ERROR 帧 + PROTOCOL_ERROR 关闭连接）")
                .isInstanceOf(MessagingException.class)
                .hasMessageNotContaining(VALID_TOKEN);
    }

    @Test
    @DisplayName("Authorization 原生头缺失：拒绝且不触达 TokenVerifier（无令牌无需进校验链）")
    void rejectsConnectWithoutAuthorizationHeaderBeforeVerification() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame(null), channel))
                .as("头缺失视同拒绝")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("非 Bearer 方案（如 Basic）：拒绝且不触达 TokenVerifier")
    void rejectsConnectWithNonBearerScheme() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Basic bnVyc2U6cGFzcw=="), channel))
                .as("非 Bearer 方案视同拒绝")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("Bearer 后空白：视同令牌缺失，拒绝且不触达 TokenVerifier")
    void rejectsConnectWhenBearerValueIsBlank() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer "), channel))
                .as("Bearer 空白视同缺失")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("非 CONNECT 帧（SUBSCRIBE 等）直通不鉴权（CONNECTED 前订阅不可达，无需重复校验）")
    void passesThroughNonConnectFramesWithoutVerification() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        Message<byte[]> subscribeFrame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(subscribeFrame, channel);

        assertThat(result).as("非 CONNECT 帧原样直通").isSameAs(subscribeFrame);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    /**
     * 构造 CONNECT 帧消息（标准测试范式：StompHeaderAccessor 创建后取消息头组装 Message，
     * 保证 MessageHeaderAccessor.getAccessor 能从帧上还原 accessor）。
     *
     * @param authorizationHeader Authorization 原生头值，可空模拟缺失
     * @return CONNECT 帧消息，非空
     */
    private static Message<byte[]> connectFrame(String authorizationHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorizationHeader != null) {
            accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
