package com.fuyun.iot.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuyun.system.api.TokenPrincipal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * /ws/iot 订阅防线拦截器单测（PR-4D Task 7，W-90 WS 面——五用例 TDD 先红后绿）：登录态订任意
 * /topic 主题放行、哨兵订三主题族前缀（telemetry/alarm/device-status）放行、哨兵订
 * dashboard/global 精确放行（多一层尾段即越面拒）、哨兵订白名单外主题拒（异常消息含
 * destination——排障锚点，禁打令牌）、泛哨兵（wardId=null）同白名单语义（wardId 段归属核验
 * 因 W-74 双标识映射缺失不校验）；分支补全：无会话主体（异常态未 CONNECT 即订阅）拒
 * （fail-closed）、destination 缺失视同越面拒、非 SUBSCRIBE 帧直通。帧构造对齐
 * NursingSubscribeWardInterceptorTest 既有 StompHeaderAccessor + MessageBuilder 手法（会话主体
 * 经 setSessionAttributes 承载，与 StompSubProtocolHandler 真实链路同一入口）。
 */
@ExtendWith(MockitoExtension.class)
class IotSubscribeInterceptorTest {

    /** 哨兵样本绑定病区（单测假值，仅具单测意义） */
    private static final String SENTINEL_WARD = "1001";

    @Mock
    private MessageChannel channel;

    private IotSubscribeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new IotSubscribeInterceptor();
    }

    @Test
    @DisplayName("登录态订阅任意 /topic 主题放行（含白名单外主题——iot 主题族无登录态限行面）")
    void loginStateSubscribeAnyTopicPasses() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(1L, "admin", null));

        // 白名单外主题（护理 board 族与其他 iot 主题）：登录态不在哨兵限订面，均放行
        assertThatCode(() -> interceptor.preSend(subscribeFrame("/topic/nursing/board/W01", attrs), channel))
                .as("登录态订护理 board 主题应放行（iot 防线仅拦哨兵越面）")
                .doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preSend(subscribeFrame("/topic/iot/other", attrs), channel))
                .as("登录态订白名单外 iot 主题应放行")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("哨兵订三主题族前缀主题放行（telemetry/alarm/device-status——wardId 尾段任意）")
    void sentinelSubscribeThreePrefixFamiliesPasses() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        assertThatCode(() ->
                        interceptor.preSend(subscribeFrame("/topic/iot/telemetry/" + SENTINEL_WARD, attrs), channel))
                .as("哨兵订遥测摘要主题应放行")
                .doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preSend(subscribeFrame("/topic/iot/alarm/" + SENTINEL_WARD, attrs), channel))
                .as("哨兵订告警主题应放行")
                .doesNotThrowAnyException();
        assertThatCode(() -> interceptor.preSend(
                        subscribeFrame("/topic/iot/device-status/" + SENTINEL_WARD, attrs), channel))
                .as("哨兵订设备状态主题应放行")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("哨兵订 dashboard/global 精确放行（多一层尾段不等于精确值即越面拒）")
    void sentinelSubscribeDashboardGlobalExactMatchPasses() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        assertThatCode(() -> interceptor.preSend(subscribeFrame("/topic/iot/dashboard/global", attrs), channel))
                .as("哨兵订全院运营摘要主题（精确匹配）应放行")
                .doesNotThrowAnyException();
        // 精确语义：global 加尾段（如 /topic/iot/dashboard/global/W01）不等于精确值，属白名单外
        assertThatThrownBy(() -> interceptor.preSend(subscribeFrame("/topic/iot/dashboard/global/W01", attrs), channel))
                .as("dashboard/global 为精确匹配，多一层尾段必须拒绝")
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("/topic/iot/dashboard/global/W01");
    }

    @Test
    @DisplayName("哨兵订白名单外主题拒：异常消息含 destination（排障锚点，禁打令牌）")
    void sentinelSubscribeOutsideWhitelistRejected() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        // 护理 board 族：跨模块主题越面（W-90 收窄前哨兵可订任意 /topic/** 的增量语义）
        assertThatThrownBy(() -> interceptor.preSend(subscribeFrame("/topic/nursing/board/W01", attrs), channel))
                .as("哨兵订护理 board 主题必须拒绝（ERROR 帧 + PROTOCOL_ERROR 关闭连接）")
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("/topic/nursing/board/W01");
        // 白名单外 iot 主题：不在四主题面
        assertThatThrownBy(() -> interceptor.preSend(subscribeFrame("/topic/iot/other", attrs), channel))
                .as("哨兵订白名单外 iot 主题必须拒绝")
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("/topic/iot/other");
    }

    @Test
    @DisplayName("泛哨兵（wardId=null）同白名单语义：白名单内放行、白名单外拒（wardId 段不校验——W-74）")
    void sentinelWithoutWardBindingSharesWhitelistSemantics() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", null));

        // W-74 双标识映射缺失：wardId 尾段与令牌绑定病区的一致性不校验，泛哨兵同白名单语义
        assertThatCode(() ->
                        interceptor.preSend(subscribeFrame("/topic/iot/telemetry/" + SENTINEL_WARD, attrs), channel))
                .as("泛哨兵订白名单内主题应放行（段级核验待 W-74 收口后升级）")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> interceptor.preSend(subscribeFrame("/topic/iot/other", attrs), channel))
                .as("泛哨兵订白名单外主题同拒（白名单语义与绑定哨兵一致）")
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("无会话主体（异常态未 CONNECT 即订阅）：拒绝——防线 fail-closed")
    void subscribeWithoutSessionPrincipalRejected() {
        assertThatThrownBy(() -> interceptor.preSend(
                        subscribeFrame("/topic/iot/telemetry/" + SENTINEL_WARD, new HashMap<>()), channel))
                .as("缺少 CONNECT 阶段注入的会话主体必须拒绝")
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("哨兵 SUBSCRIBE 帧缺 destination：视同越面拒（STOMP 规范必填，缺失属畸形帧防御）")
    void sentinelSubscribeWithoutDestinationRejected() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setSessionAttributes(attrs);
        Message<byte[]> frame = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(frame, channel))
                .as("destination 缺失不得进白名单判定（startsWith null 会 NPE，须前置分流为拒）")
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("非 SUBSCRIBE 帧直通不校验（CONNECT 鉴权归第一拦截器，SEND/DISCONNECT 无订阅语义）")
    void nonSubscribeFramesPassThrough() {
        Map<String, Object> attrs = withPrincipal(new TokenPrincipal(0L, "bigscreen", SENTINEL_WARD));

        StompHeaderAccessor sendAccessor = StompHeaderAccessor.create(StompCommand.SEND);
        sendAccessor.setDestination("/topic/nursing/board/W01");
        sendAccessor.setSessionAttributes(attrs);
        Message<byte[]> sendFrame = MessageBuilder.createMessage(new byte[0], sendAccessor.getMessageHeaders());
        assertThatCode(() -> interceptor.preSend(sendFrame, channel))
                .as("SEND 帧不在本防线面（iot 防线仅拦订阅）")
                .doesNotThrowAnyException();

        StompHeaderAccessor disconnectAccessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnectAccessor.setSessionAttributes(attrs);
        Message<byte[]> disconnectFrame =
                MessageBuilder.createMessage(new byte[0], disconnectAccessor.getMessageHeaders());
        assertThatCode(() -> interceptor.preSend(disconnectFrame, channel))
                .as("DISCONNECT 帧无订阅语义直通")
                .doesNotThrowAnyException();
    }

    /**
     * 组装携带会话主体的属性表（与 CONNECT 拦截器注入键同源——{@code ATTR_TOKEN_PRINCIPAL}）。
     *
     * @param principal CONNECT 阶段缓存的令牌主体，非空
     * @return 含主体的会话属性表，非空
     */
    private static Map<String, Object> withPrincipal(TokenPrincipal principal) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(StompConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL, principal);
        return attrs;
    }

    /**
     * 构造 SUBSCRIBE 帧消息（标准测试范式：StompHeaderAccessor 创建后设目的地与会话属性再组装
     * Message，保证 MessageHeaderAccessor.getAccessor 能从帧上还原 accessor 与 simpSessionAttributes）。
     *
     * @param destination      订阅目的地，非空
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
