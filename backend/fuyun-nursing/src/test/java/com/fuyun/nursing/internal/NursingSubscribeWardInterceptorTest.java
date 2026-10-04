package com.fuyun.nursing.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.api.NursingErrorCode;
import com.fuyun.nursing.service.IWardAccessService;
import com.fuyun.system.api.TokenPrincipal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * /ws/nursing SUBSCRIBE 病区防线拦截器单测（PR-4C Task 7 A-2——GC10 全分支覆盖）：哨兵令牌
 * 区内订阅放行/越区拒、泛哨兵（wardId=null）一律拒、登录态绑定集内过/集外拒（BizException 经
 * 拦截器转 MessagingException——ERROR 帧 + 连接关闭语义）、无会话主体（异常态未 CONNECT）拒、
 * 非 board 主题与非 SUBSCRIBE 帧原样放行。帧构造对齐 NursingConnectAuthInterceptorTest 既有
 * StompHeaderAccessor + MessageBuilder 手法（会话主体经 simpSessionAttributes 头承载，与
 * StompSubProtocolHandler 真实链路同一入口）。
 */
@ExtendWith(MockitoExtension.class)
class NursingSubscribeWardInterceptorTest {

    /** 哨兵样本令牌绑定病区（单测假值，仅具单测意义） */
    private static final String SENTINEL_WARD = "1001";

    @Mock
    private IWardAccessService wardAccessService;

    @Mock
    private MessageChannel channel;

    private NursingSubscribeWardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new NursingSubscribeWardInterceptor(wardAccessService);
    }

    @Test
    @DisplayName("哨兵区内订阅：destination 尾段==令牌绑定病区，帧原样放行（大屏单病区通道主路径）")
    void sentinelSubscribeWithinBoundWardPasses() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        Message<byte[]> frame = subscribeFrame("/topic/nursing/board/" + SENTINEL_WARD, attrs);

        assertThatCode(() -> interceptor.preSend(frame, channel))
                .as("哨兵区内订阅应放行（不触发病区归属服务——哨兵分支拦截器先行处理）")
                .doesNotThrowAnyException();
        verifyNoInteractions(wardAccessService);
    }

    @Test
    @DisplayName("哨兵越区订阅：全院 board 越层订阅被拒（A-2 主断言，MessagingException 转 ERROR 帧）")
    void sentinelSubscribeOutsideBoundWardRejected() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        assertThatThrownBy(() -> interceptor.preSend(subscribeFrame("/topic/nursing/board/9999", attrs), channel))
                .as("越区订阅必须拒绝（ERROR 帧 + PROTOCOL_ERROR 关闭连接）")
                .isInstanceOf(MessagingException.class);
        verifyNoInteractions(wardAccessService);
    }

    @Test
    @DisplayName("泛哨兵（wardId=null）nursing board 订阅一律拒（候诊屏令牌不得订护理板）")
    void sentinelWithoutWardBindingRejectedOnAnyBoardSubscribe() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", null));

        assertThatThrownBy(() ->
                        interceptor.preSend(subscribeFrame("/topic/nursing/board/" + SENTINEL_WARD, attrs), channel))
                .as("泛哨兵任何 board 订阅均应拒绝")
                .isInstanceOf(MessagingException.class);
        verifyNoInteractions(wardAccessService);
    }

    @Test
    @DisplayName("登录态订阅：绑定集内过、集外拒（fail-closed；BizException 经拦截器转 MessagingException）")
    void loginSubscribeWithinBindingsPassesAndOutsideRejected() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(1L, "admin", null));

        // 集内：病区归属服务校验通过（void 无异常）——订阅放行
        assertThatCode(() -> interceptor.preSend(subscribeFrame("/topic/nursing/board/W-IT-9006", attrs), channel))
                .as("登录态当班绑定集内订阅应放行")
                .doesNotThrowAnyException();
        // 集外：服务抛 NS-1028（403，含查无绑定 fail-closed 同型）——拦截器转 ERROR 帧关闭语义
        doThrow(new BizException(NursingErrorCode.WARD_ACCESS_DENIED, HttpStatus.FORBIDDEN))
                .when(wardAccessService)
                .assertWardAllowedFor("1", "W02");
        assertThatThrownBy(() -> interceptor.preSend(subscribeFrame("/topic/nursing/board/W02", attrs), channel))
                .as("登录态越区订阅必须拒绝（MessagingException——ERROR 帧 + 连接关闭）")
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("无会话主体（异常态未 CONNECT 即订阅 board）：拒绝——防线 fail-closed")
    void boardSubscribeWithoutSessionPrincipalRejected() {
        assertThatThrownBy(() -> interceptor.preSend(
                        subscribeFrame("/topic/nursing/board/" + SENTINEL_WARD, new HashMap<>()), channel))
                .as("缺少 CONNECT 阶段注入的会话主体必须拒绝")
                .isInstanceOf(MessagingException.class);
        verifyNoInteractions(wardAccessService);
    }

    @Test
    @DisplayName("非 board 主题订阅：不在防线面，无主体亦原样放行（未来扩展位——A-2 原文范围）")
    void nonBoardDestinationSubscribePassesThrough() {
        assertThatCode(() ->
                        interceptor.preSend(subscribeFrame("/topic/nursing/vital-signs", new HashMap<>()), channel))
                .as("非 board 主题订阅应原样放行（CONNECT 帧鉴权已承担会话准入门）")
                .doesNotThrowAnyException();
        verifyNoInteractions(wardAccessService);
    }

    @Test
    @DisplayName("非 SUBSCRIBE 帧（SEND 等）直通不校验（即使 destination 命中 board 前缀）")
    void nonSubscribeFrameToBoardDestinationPassesThrough() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/topic/nursing/board/" + SENTINEL_WARD);
        accessor.setSessionAttributes(attrs);
        Message<byte[]> sendFrame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatCode(() -> interceptor.preSend(sendFrame, channel))
                .as("非 SUBSCRIBE 帧不在防线面（SEND 由消息链路自身守卫承担）")
                .doesNotThrowAnyException();
        verifyNoInteractions(wardAccessService);
    }

    /**
     * 组装携带会话主体的属性表（与 CONNECT 拦截器注入键同源——{@code ATTR_TOKEN_PRINCIPAL}）。
     *
     * @param principal CONNECT 阶段缓存的令牌主体，非空
     * @return 含主体的会话属性表，非空
     */
    private static Map<String, Object> withPrincipal(TokenPrincipal principal) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(NursingConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL, principal);
        return attrs;
    }

    /**
     * 构造 SUBSCRIBE 帧消息（标准测试范式：StompHeaderAccessor 创建后设目的地与会话属性再组装
     * Message，保证 MessageHeaderAccessor.getAccessor 能从帧上还原 accessor 与 simpSessionAttributes）。
     *
     * @param destination     订阅目的地，非空
     * @param sessionAttributes 会话属性表，非空（可为空表模拟异常态缺主体）
     * @return SUBSCRIBE 帧消息，非空
     */
    private static Message<byte[]> subscribeFrame(String destination, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionAttributes(sessionAttributes);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
