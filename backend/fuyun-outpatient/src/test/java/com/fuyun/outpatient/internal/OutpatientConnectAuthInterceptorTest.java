package com.fuyun.outpatient.internal;

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
 * 门诊 STOMP CONNECT 帧级鉴权拦截器单元测试（Task 7 镜像 iot 侧同名单——brief 冻结六例）：
 * 合法 Bearer 令牌放行、Authorization 头缺失拒绝、非 Bearer 方案拒绝、令牌校验未过拒绝、
 * 非 CONNECT 帧直通、拒绝异常固定摘要（不含令牌防枚举/防泄露）。真实容器 CONNECT 拒绝链路
 * （ERROR 帧 + PROTOCOL_ERROR 关闭）归 Task 15 真栈 IT。
 */
@ExtendWith(MockitoExtension.class)
class OutpatientConnectAuthInterceptorTest {

    /** 测试令牌样本（无意义假值，仅具单测意义；真实令牌经 M01 登录签发） */
    private static final String VALID_TOKEN = "op-fake-access-token";

    /** 拒绝异常固定摘要（镜像主类字面：进 ERROR 帧 message 头，不含令牌与拒绝原因） */
    private static final String FIXED_REJECT_MESSAGE = "CONNECT 帧鉴权未通过，连接已被服务端拒绝";

    @Mock
    private TokenVerifier tokenVerifier;

    @Mock
    private MessageChannel channel;

    private OutpatientConnectAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new OutpatientConnectAuthInterceptor(tokenVerifier);
    }

    @Test
    @DisplayName("合法 Bearer 令牌的 CONNECT 帧：TokenVerifier 校验通过后帧原样放行")
    void connectPassesWithValidBearerToken() {
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(true);

        Message<byte[]> connectFrame = connectFrame("Bearer " + VALID_TOKEN);

        Message<?> result = interceptor.preSend(connectFrame, channel);

        assertThat(result).as("校验通过帧原样放行").isSameAs(connectFrame);
    }

    @Test
    @DisplayName("Authorization 原生头缺失：拒绝且不触达 TokenVerifier（无令牌无需进校验链）")
    void connectRejectsWithoutAuthorizationHeader() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame(null), channel))
                .as("头缺失视同拒绝")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("非 Bearer 方案（如 Basic）：拒绝且不触达 TokenVerifier")
    void connectRejectsWithNonBearerScheme() {
        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Basic b3AtdXNlcjpvcC1wYXNz"), channel))
                .as("非 Bearer 方案视同拒绝")
                .isInstanceOf(MessagingException.class);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("令牌校验未通过：抛 MessagingException 拒绝 CONNECT（异常消息不含令牌值防泄露）")
    void connectRejectsWithInvalidToken() {
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer " + VALID_TOKEN), channel))
                .as("校验未过必须拒绝 CONNECT（服务端将以 ERROR 帧 + 关闭连接响应客户端）")
                .isInstanceOf(MessagingException.class)
                .hasMessageNotContaining(VALID_TOKEN);
    }

    @Test
    @DisplayName("非 CONNECT 帧（SUBSCRIBE 等）直通不鉴权（CONNECTED 前订阅不可达，无需重复校验）")
    void nonConnectFramePassesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setNativeHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        Message<byte[]> subscribeFrame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(subscribeFrame, channel);

        assertThat(result).as("非 CONNECT 帧原样直通").isSameAs(subscribeFrame);
        verify(tokenVerifier, never()).verifyAccessToken(anyString());
    }

    @Test
    @DisplayName("拒绝异常携带固定摘要：字面与镜像源一致且不含令牌（防枚举/防泄露，进 ERROR 帧）")
    void rejectionThrowsMessagingExceptionWithFixedMessage() {
        when(tokenVerifier.verifyAccessToken(VALID_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer " + VALID_TOKEN), channel))
                .isInstanceOf(MessagingException.class)
                .hasMessage(FIXED_REJECT_MESSAGE)
                .hasMessageNotContaining(VALID_TOKEN);
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
