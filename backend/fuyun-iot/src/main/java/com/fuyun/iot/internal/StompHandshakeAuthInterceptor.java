package com.fuyun.iot.internal;

import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.system.api.TokenVerifier;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * STOMP 握手鉴权拦截器（B4.3，BRIEF-PR4-01 §1.4）：/ws/iot 端点握手期校验 M01 访问令牌，
 * 失败拒绝握手（401）——未认证连接不得建立 WebSocket 通道（14-iot §9 安全红线）。
 *
 * <p>校验链：取 {@code Authorization: Bearer {token}} 头 → {@link TokenVerifier#verifyAccessToken}
 * 布尔校验（签名/过期/typ/会话存在全链，失败不区分原因防枚举）→ 未过置 401 拒绝。为何用布尔
 * 契约：握手失败出口仅状态码无 ProblemDetail 渲染通道（TokenVerifier javadoc 口径）；拒绝原因
 * 三态（头缺失/格式不符/校验未过）合并单条 warn，日志不含令牌值（红线 6）。
 *
 * <p>无状态单例：TokenVerifier 实现线程安全（TokenServiceImpl 无状态契约）。归 internal/ 包
 * （模块内基础设施，禁止外部引用，宪法 B.1）；Bean 注册点为 IotWebSocketConfig @Import。
 */
@Slf4j
public class StompHandshakeAuthInterceptor implements HandshakeInterceptor {

    /** Bearer 方案前缀（RFC 6750，与工作站 Axios 拦截器拼接口径一致） */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 访问令牌布尔校验契约（跨模块最小暴露面，宪法 B.2-2 只依赖 api 包） */
    private final TokenVerifier tokenVerifier;

    /**
     * 全参构造器（装配归 IotWebSocketConfig @Import，backend 宪法 B.1）。
     *
     * @param tokenVerifier 访问令牌校验器，非空；来源：SystemWebConfig tokenVerifier Bean
     */
    public StompHandshakeAuthInterceptor(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    /**
     * 握手前鉴权：Bearer 令牌全链校验，未通过置 401 拒绝。
     *
     * @param request    握手请求，非空；Authorization 头可缺失
     * @param response   握手响应，非空；拒绝时置 401 状态码
     * @param wsHandler  目标 WebSocket 处理器，非空（本拦截器不消费）
     * @param attributes 握手属性（P0 不向会话透传身份，订阅级数据范围校验属 P1）
     * @return true=放行握手；false=拒绝（响应已置 401）
     */
    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String token = extractBearerToken(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (token != null && tokenVerifier.verifyAccessToken(token)) {
            log.info(
                    "STOMP 握手鉴权通过：uri={}，traceId={}",
                    request.getURI(),
                    MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
            return true;
        }
        // warn 不含令牌值（红线 6）；拒绝原因三态合并表述，防客户端按差异枚举探测
        log.warn(
                "STOMP 握手鉴权拒绝（令牌缺失、格式不符或校验未通过）：uri={}，traceId={}",
                request.getURI(),
                MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    /** 握手完成后无补作（P0 契约空实现；连接级身份透传随 P1 订阅数据范围校验演进）。 */
    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        // 空实现：接口要求的生命周期钩子，P0 无握手后置动作
    }

    /**
     * 提取 Bearer 方案令牌。
     *
     * @param authorizationHeader Authorization 头原文，可空
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
