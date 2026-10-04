package com.fuyun.nursing.internal;

import com.fuyun.common.exception.BizException;
import com.fuyun.nursing.constants.NursingSecurityConstants;
import com.fuyun.nursing.service.IWardAccessService;
import com.fuyun.system.api.TokenPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * /ws/nursing STOMP SUBSCRIBE 病区防线拦截器（PR-4C Task 7 A-2，评审 75 分门槛项）：board 主题族
 * 的病区级订阅限行——哨兵令牌单病区（destination 尾段==令牌绑定病区，泛哨兵一律拒）、登录态
 * 限当班绑定集（fail-closed，D-29：查无绑定同拒，ADMIN 无豁免）。
 *
 * <p><b>防线语义</b>：仅拦 SUBSCRIBE 帧且 destination 以 board 前缀开头（
 * {@link NurseBoardPushListener#BOARD_TOPIC_PREFIX}——与推送出口逐字同源，防两侧漂移）；其余
 * destination 不在防线面（A-2 原文范围即 board 族；iot/outpatient WS 主题族无病区隔离语义，
 * 不挂本防线）。主体取自 CONNECT 阶段 {@link NursingConnectAuthInterceptor} 缓存的会话属性
 * {@link NursingConnectAuthInterceptor#ATTR_TOKEN_PRINCIPAL}（连接级生命周期，零二次 Redis 读）；
 * 无主体（异常态未 CONNECT 即订阅）拒——防线 fail-closed，不因链路异常放行。
 *
 * <p><b>拒绝口径（GC12）</b>：日志记 destination/绑定病区/operator（哨兵记绑定病区、登录态记
 * operatorId），禁打令牌内容；抛 {@link MessagingException}（登录态为 BizException NS-1028 的
 * 转译，403 语义经 MessagingException 转 ERROR 帧 + PROTOCOL_ERROR 关闭连接——与 CONNECT 拒绝
 * 同一传输语义，spring-websocket 6.2.19 字节码同源）。哨兵分支在本拦截器先行分流，不落
 * {@link IWardAccessService}（service 侧哨兵豁免仅为 HTTP 面防御纵深）。
 *
 * <p>无状态单例。归 internal/ 包（禁外引，宪法 B.1）；Bean 注册点 NursingWebSocketConfig
 * @Import，挂载于 clientInboundChannel（双拦截器序第二位，CONNECT 鉴权在前注入主体）。
 */
@Slf4j
public class NursingSubscribeWardInterceptor implements ChannelInterceptor {

    /** 病区归属校验服务（登录态当班绑定集判定，显式传参版——broker 线程无 ThreadLocal） */
    private final IWardAccessService wardAccessService;

    /**
     * 全参构造器（装配归 NursingWebSocketConfig @Import，backend 宪法 B.1）。
     *
     * @param wardAccessService 病区归属校验服务，非空；来源：NursingWebConfig @Import 的
     *                          WardAccessServiceImpl Bean
     */
    public NursingSubscribeWardInterceptor(IWardAccessService wardAccessService) {
        this.wardAccessService = wardAccessService;
    }

    /**
     * SUBSCRIBE 病区防线：board 主题族订阅按主体形态限行——哨兵要求尾段==令牌绑定病区（null 一律
     * 拒），登录态要求尾段 ∈ 当班绑定集（fail-closed）；其余帧与 destination 原样放行。
     *
     * @param message 入站消息，非空；SUBSCRIBE 帧的会话属性表由 StompSubProtocolHandler 建帧时
     *                注入（WS 会话 attributes 原引用，含 CONNECT 阶段缓存的令牌主体）
     * @param channel 入站通道（clientInboundChannel），非空（本拦截器不直接使用）
     * @return 原消息（放行）；拒绝时不返回而是抛 MessagingException
     * @throws MessagingException 无会话主体（异常态未 CONNECT）、哨兵越区/泛哨兵、登录态集外/
     *                             查无绑定（BizException NS-1028 转译，语义等价 fail-closed）；
     *                             客户端将收到 ERROR 帧且连接被服务端关闭
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            // 非 SUBSCRIBE 帧放行（CONNECT 鉴权由第一拦截器承担，SEND 由消息链路自身守卫）
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(NurseBoardPushListener.BOARD_TOPIC_PREFIX)) {
            // 非 board 主题不在防线面（A-2 原文范围——未来扩展位照此扩展）
            return message;
        }
        // 主体消费：CONNECT 阶段缓存的连接级会话属性（会话属性表缺失视同无主体，fail-closed）
        Object raw = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(NursingConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL);
        if (!(raw instanceof TokenPrincipal principal)) {
            log.warn(
                    "board 订阅缺少已鉴权会话主体（异常态未 CONNECT 即订阅）：sessionId={}，destination={}",
                    accessor.getSessionId(),
                    destination);
            throw new MessagingException("订阅缺少已鉴权会话主体，订阅已被服务端拒绝");
        }
        String wardId = destination.substring(NurseBoardPushListener.BOARD_TOPIC_PREFIX.length());
        if (NursingSecurityConstants.BIGSCREEN_LOGIN_NAME.equals(principal.loginName())) {
            // 哨兵：单病区通道——尾段必须等于令牌绑定病区（泛哨兵 null 一律拒，候诊屏令牌不得订护理板）
            if (principal.wardId() == null || !principal.wardId().equals(wardId)) {
                // GC12：记 destination/绑定病区，禁打令牌内容
                log.warn(
                        "哨兵越区订阅被拒：sessionId={}，destination={}，绑定病区={}",
                        accessor.getSessionId(),
                        destination,
                        principal.wardId());
                throw new MessagingException("大屏匿名令牌仅可订阅绑定病区的看板主题");
            }
            return message;
        }
        // 登录态：尾段 ∈ 当班绑定集（显式传参——broker 线程无 ThreadLocal 身份）；集外/查无绑定
        // 由服务侧 NS-1028（403）拒绝，此处转 MessagingException 统一 ERROR 帧关闭语义
        try {
            wardAccessService.assertWardAllowedFor(String.valueOf(principal.userId()), wardId);
        } catch (BizException e) {
            // GC12：记 destination/operatorId（日志取 userId，不涉令牌与敏感身份字段）
            log.warn(
                    "登录态越区订阅被拒：sessionId={}，destination={}，operatorId={}",
                    accessor.getSessionId(),
                    destination,
                    principal.userId());
            throw new MessagingException("订阅病区不在当班绑定范围，订阅已被服务端拒绝", e);
        }
        return message;
    }
}
