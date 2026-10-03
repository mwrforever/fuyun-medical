package com.fuyun.nursing.internal;

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
 * /ws/nursing STOMP CONNECT 帧级鉴权拦截器（Task 11——照抄 iot {@code StompConnectAuthInterceptor}
 * 形态，OutpatientConnectAuthInterceptor 镜像同款）：/ws/nursing 端点的 M01 访问令牌校验点。
 *
 * <p><b>鉴权形态实测结论（派发上下文 §2.1）</b>：匿名大屏令牌签发面=system
 * {@code POST /api/v1/system/auth/bigscreen-token}（匿名白名单，5 分钟短期单 access 令牌，
 * <b>与登录 access 同构</b>）；故本拦截器与 iot 侧同一条 {@code TokenVerifier#verifyAccessToken}
 * 布尔校验链两态通吃——workstation 登录态（登录 access）与 bigscreen 护士站大屏匿名态
 * （bigscreen-token 运行期签发，前端 Task 17 照 useQueueStomp 先例 beforeConnect 签发）均过
 * 同一校验，无需区分医护/匿名形态。
 *
 * <p>安全等价声明与拒绝语义（iot 侧 spring-websocket 6.2.19 字节码实证同源）：升级端点允许
 * 匿名建立 WebSocket 传输层，但任何 STOMP 会话必须先通过 CONNECT 帧
 * {@code Authorization: Bearer {token}} 校验方可 CONNECTED——SimpleBroker 仅在 CONNECTED 后
 * 接受 SUBSCRIBE，未授权会话无法订阅/收发任何数据（订阅前无数据暴露）；preSend 抛
 * {@link MessagingException} → 客户端收 ERROR 帧并以 PROTOCOL_ERROR 关闭连接。拒绝原因三态
 * （头缺失/格式不符/校验未过）合并单条 warn，日志不含令牌值（红线 6）。仅拦截 CONNECT 帧：
 * SUBSCRIBE/SEND 等后续帧仅在 CONNECTED 后可达，无需重复鉴权。
 *
 * <p>无状态单例（TokenServiceImpl 无状态契约）。归 internal/ 包（禁外引，宪法 B.1）；Bean
 * 注册点 NursingWebSocketConfig @Import，挂载于 clientInboundChannel。
 */
@Slf4j
public class NursingConnectAuthInterceptor implements ChannelInterceptor {

    /** MDC traceId 键（全链路贯穿口径——GlobalExceptionHandler/IotMessagingConstants 同值） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /** Bearer 方案前缀（RFC 6750，与工作站 Axios 拦截器拼接口径一致） */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 访问令牌布尔校验契约（跨模块最小暴露面，宪法 B.2-2 只依赖 api 包） */
    private final TokenVerifier tokenVerifier;

    /**
     * 全参构造器（装配归 NursingWebSocketConfig @Import，backend 宪法 B.1）。
     *
     * @param tokenVerifier 访问令牌校验器，非空；来源：SystemWebConfig tokenVerifier Bean
     */
    public NursingConnectAuthInterceptor(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    /**
     * CONNECT 帧鉴权：Bearer 令牌全链校验，未通过抛 MessagingException 拒绝该帧（拒绝语义见类
     * javadoc——ERROR 帧 + PROTOCOL_ERROR 关闭连接）；非 CONNECT 帧原样放行。
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
                    "护理大屏 STOMP CONNECT 帧鉴权通过：sessionId={}，traceId={}",
                    accessor.getSessionId(),
                    MDC.get(TRACE_ID_MDC_KEY));
            return message;
        }
        // warn 不含令牌值（红线 6）；拒绝原因三态合并表述，防客户端按差异枚举探测
        log.warn(
                "护理大屏 STOMP CONNECT 帧鉴权拒绝（令牌缺失、格式不符或校验未通过）：sessionId={}，traceId={}",
                accessor.getSessionId(),
                MDC.get(TRACE_ID_MDC_KEY));
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
