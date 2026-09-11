package com.fuyun.iot.internal;

import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.system.api.TokenVerifier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * STOMP CONNECT 帧级鉴权拦截器（PR-5 独立审查 Finding 1）：/ws/iot 端点的 M01 访问令牌校验点
 * 由 HTTP 握手层迁移至此——浏览器原生 WebSocket API 无法携带自定义 HTTP 头，stompjs
 * connectHeaders 只进入 WebSocket 建立后的 CONNECT 帧，握手层鉴权对浏览器客户端必然 401
 * （原 StompHandshakeAuthInterceptor 已删除，鉴权逻辑全部迁至本类）。
 *
 * <p>安全等价声明：/ws/iot 升级端点允许匿名建立 WebSocket 传输层，但任何 STOMP 会话必须先
 * 通过 CONNECT 帧令牌校验方可 CONNECTED——SimpleBroker 仅在 CONNECTED 后接受 SUBSCRIBE，
 * 未授权会话无法订阅/收发任何数据（订阅前无数据暴露），鉴权时点仍先于一切数据通道
 * （14-iot §9 安全红线）。
 *
 * <p>拒绝语义（spring-websocket 6.2.19 StompSubProtocolHandler 字节码实证）：preSend 抛
 * {@link MessagingException} → StompSubProtocolHandler 捕获后经 StompSubProtocolErrorHandler
 * 向客户端回 ERROR 帧（message 头 = 本异常消息，故取不含令牌与拒绝原因的固定摘要，防枚举）
 * → 服务端随即以 CloseStatus.PROTOCOL_ERROR 关闭连接——客户端收到 ERROR 帧后连接关闭。
 *
 * <p>校验链：CONNECT 帧原生头 {@code Authorization: Bearer {token}} →
 * {@link TokenVerifier#verifyAccessToken} 布尔校验（签名/过期/typ/会话存在全链，失败不区分
 * 原因防枚举）；拒绝原因三态（头缺失/格式不符/校验未过）合并单条 warn，日志不含令牌值（红线 6）。
 * 仅拦截 CONNECT 帧：SUBSCRIBE/SEND 等后续帧仅在 CONNECTED 后可达，无需重复鉴权。
 *
 * <p>无状态单例：TokenVerifier 实现线程安全（TokenServiceImpl 无状态契约）。归 internal/ 包
 * （模块内基础设施，禁止外部引用，宪法 B.1）；Bean 注册点为 IotWebSocketConfig @Import，
 * 挂载于 clientInboundChannel（configureClientInboundChannel）。
 */
@Slf4j
public class StompConnectAuthInterceptor implements ChannelInterceptor {

    /** Bearer 方案前缀（RFC 6750，与工作站 Axios 拦截器拼接口径一致） */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 访问令牌布尔校验契约（跨模块最小暴露面，宪法 B.2-2 只依赖 api 包） */
    private final TokenVerifier tokenVerifier;

    /**
     * 全参构造器（装配归 IotWebSocketConfig @Import，backend 宪法 B.1）。
     *
     * @param tokenVerifier 访问令牌校验器，非空；来源：SystemWebConfig tokenVerifier Bean
     */
    public StompConnectAuthInterceptor(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    /**
     * CONNECT 帧鉴权：Bearer 令牌全链校验，未通过抛 MessagingException 拒绝该帧（拒绝语义
     * 见类 javadoc——ERROR 帧 + PROTOCOL_ERROR 关闭连接）；非 CONNECT 帧原样放行。
     *
     * @param message 入站消息，非空；CONNECT 帧须携带 Authorization 原生头（Bearer 方案）
     * @param channel 入站通道（clientInboundChannel），非空（本拦截器不直接使用）
     * @return 原消息（放行）；拒绝时不返回而是抛 MessagingException
     * @throws MessagingException 令牌缺失、格式不符或校验未通过（三态合并，不区分原因防枚举）；
     *                            客户端将收到 ERROR 帧且连接被服务端关闭
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !SimpMessageType.CONNECT.equals(accessor.getMessageType())) {
            return message;
        }
        String token = extractBearerToken(accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION));
        if (token != null && tokenVerifier.verifyAccessToken(token)) {
            log.info(
                    "STOMP CONNECT 帧鉴权通过：sessionId={}，traceId={}",
                    accessor.getSessionId(),
                    MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
            return message;
        }
        // warn 不含令牌值（红线 6）；拒绝原因三态合并表述，防客户端按差异枚举探测
        log.warn(
                "STOMP CONNECT 帧鉴权拒绝（令牌缺失、格式不符或校验未通过）：sessionId={}，traceId={}",
                accessor.getSessionId(),
                MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
        // 异常消息进 ERROR 帧 message 头返回客户端：固定摘要，不含令牌与拒绝原因（防枚举/防泄露）
        throw new MessagingException("CONNECT 帧鉴权未通过，连接已被服务端拒绝");
    }

    /**
     * 提取 Bearer 方案令牌。
     *
     * @param authorizationHeader Authorization 原生头原文，可空
     * @return 令牌值；头缺失、非 Bearer 方案或 Bearer 后空白返回 null（视同缺失，不进校验链）
     */
    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
