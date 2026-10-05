package com.fuyun.iot.internal;

import com.fuyun.iot.constants.IotMessagingConstants;
import com.fuyun.iot.constants.IotSecurityConstants;
import com.fuyun.system.api.TokenPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * /ws/iot STOMP 订阅防线拦截器（PR-4D Task 7，W-90 WS 面——镜像 nursing PR-4C
 * NursingSubscribeWardInterceptor 防线先例）：大屏哨兵（loginName=bigscreen）SUBSCRIBE
 * 在 iot 主题面内限订白名单四主题，登录态全放行。
 *
 * <p><b>管辖面（域防线自治，修复环裁定）</b>：clientInboundChannel 为全部 WS 端点共享的全局
 * 入站通道（nursing/iot/outpatient 三端点的拦截器均对彼此连接生效），故本防线经
 * {@link IotMessagingConstants#TOPIC_IOT_PREFIX} 前缀判定收窄管辖面——仅 /topic/iot/ 面内
 * 行使哨兵白名单；面外主题（如护理 board 族）原样放行、归各域自身防线（哨兵的 board 订阅由
 * NursingSubscribeWardInterceptor 按令牌 wardId 段一致性校验，PR-4C A-2 链路保持不变——
 * 域防线互不代管，与 nursing 侧 board 前缀管辖形态互为镜像）。
 *
 * <p><b>增量语义（如实声明）</b>：iot 面内收窄「哨兵可订任意 /topic/iot/**」为四主题面——
 * 三主题族前缀匹配（telemetry/alarm/device-status，{@code {wardId}} 尾段任意）+ 全院摘要
 * 精确匹配（/topic/iot/dashboard/global，多一层尾段即越面）；白名单词表直接复用
 * {@link IotMessagingConstants} 推送出口前缀常量（与推送侧逐字同源，防两侧漂移——不另立副本）。
 * W-90 登记前哨兵 CONNECT 通过即可订任意 /topic 主题，越面收窄为新增防御纵深。
 * 登录态（任意非哨兵 loginName）订阅不受限。<b>已知缺口申报（评审 A-5，W-94 工单在案）</b>：
 * REST 面 iot 域读端点已全量入 403 矩阵（无关角色 403 拒绝），而本防线对登录态在
 * /topic/iot/ 面内全放行——WS 与 REST 纵深不对齐（REST 收紧、WS 敞开），登录态订阅限行
 * （镜像矩阵允许集或「已授予任一 iot 域读权限点的角色」口径）待 W-94 与 alarms 端点入
 * 矩阵统一收敛；wardId 段级核验仍按 W-74 节奏收口。
 *
 * <p><b>wardId 段归属核验缺位申报（W-74，javadoc 注记）</b>：三主题族 {@code {wardId}} 尾段与
 * 哨兵令牌绑定病区的一致性本防线不校验——iot 数字病区 id 与护理病区编码双标识空间映射缺失
 * （TASK.md W-74 工单在案），擅自造映射表属越权；待 W-74 双标识映射收口后升级为段级核验
 * （届时绑定哨兵非匹配段与泛哨兵的族内订阅应拒）。当前泛哨兵（wardId=null）与绑定哨兵同一
 * 白名单语义——四主题均 iot 展示数据，段级隔离缺口由 W-74 收口承载。
 *
 * <p>主体取自 CONNECT 阶段 {@link StompConnectAuthInterceptor} 缓存的会话属性
 * {@link StompConnectAuthInterceptor#ATTR_TOKEN_PRINCIPAL}（连接级生命周期，零二次 Redis 读）；
 * 无主体（异常态未 CONNECT 即订阅）拒——防线 fail-closed，不因链路异常放行（nursing 先例同款）。
 *
 * <p><b>拒绝口径（GC12）</b>：抛 {@link MessagingException}（客户端收 ERROR 帧并以
 * PROTOCOL_ERROR 关闭连接——与 CONNECT 拒绝同一传输语义，spring-websocket 6.2.19 同源）；
 * 异常消息含 destination 与 traceId（排障锚点，禁打令牌）；日志记 sessionId/destination，
 * 禁打令牌内容。
 *
 * <p>无状态单例。归 internal/ 包（禁外引，宪法 B.1）；Bean 注册点 IotWebSocketConfig @Import，
 * 挂载于 clientInboundChannel 双拦截器序第二位（CONNECT 鉴权在前注入主体）。
 */
@Slf4j
public class IotSubscribeInterceptor implements ChannelInterceptor {

    /**
     * 订阅防线：仅拦 SUBSCRIBE 帧——登录态全放行；哨兵在 iot 前缀面内限订四主题白名单
     * （面外主题放行归各域防线自治）；无会话主体（异常态未 CONNECT）fail-closed 拒；其余帧原样放行。
     *
     * @param message 入站消息，非空；SUBSCRIBE 帧的会话属性表由 StompSubProtocolHandler 建帧时
     *                注入（WS 会话 attributes 原引用，含 CONNECT 阶段缓存的令牌主体）
     * @param channel 入站通道（clientInboundChannel），非空（本拦截器不直接使用）
     * @return 原消息（放行）；拒绝时不返回而是抛 MessagingException
     * @throws MessagingException 无会话主体（异常态未 CONNECT）、哨兵越面（iot 面内白名单外
     *                            destination 或 destination 缺失）；客户端将收到 ERROR 帧且
     *                            连接被服务端关闭
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        // 仅 SUBSCRIBE 受防线（订阅限行）；CONNECT 鉴权由第一拦截器承担，SEND/DISCONNECT 等
        // 其余帧无订阅语义直通（iot 防线不拦 SEND——nursing A-1 的 SEND 封堵针对 board 族注入
        // 欺骗，iot 主题族无该攻击面）
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }
        String destination = accessor.getDestination();
        // 主体消费：CONNECT 阶段缓存的连接级会话属性（会话属性表缺失视同无主体，fail-closed）
        Object raw = accessor.getSessionAttributes() == null
                ? null
                : accessor.getSessionAttributes().get(StompConnectAuthInterceptor.ATTR_TOKEN_PRINCIPAL);
        if (!(raw instanceof TokenPrincipal principal)) {
            log.warn("iot 订阅帧缺少已鉴权会话主体（异常态未 CONNECT 即订阅）：sessionId={}", accessor.getSessionId());
            throw new MessagingException("订阅缺少已鉴权会话主体，已被服务端拒绝");
        }
        // 登录态（非哨兵登录名）全放行：iot 主题族暂无登录态限行面（WS 与 REST 403 矩阵纵深
        // 不对齐为已申报缺口，W-94 工单在案；哨兵锚点比对，镜像
        // NursingSecurityConstants.BIGSCREEN_LOGIN_NAME 判定形态——iot 侧镜像常量防跨模块 import）
        if (!IotSecurityConstants.BIGSCREEN_LOGIN_NAME.equals(principal.loginName())) {
            return message;
        }
        // 管辖面判定（域防线自治，修复环裁定）：iot 前缀面外主题放行——归各域自身防线（哨兵的
        // board 订阅由 NursingSubscribeWardInterceptor 按 wardId 段一致性校验，域防线互不代管）；
        // destination 为 null 不走本分流（畸形帧落白名单判定拒，保持 fail-closed）
        if (destination != null && !destination.startsWith(IotMessagingConstants.TOPIC_IOT_PREFIX)) {
            return message;
        }
        // 哨兵：iot 面内白名单四主题判定（三前缀+精确）；destination 缺失（畸形帧）视同越面拒
        if (destination != null && withinWhitelist(destination)) {
            return message;
        }
        // GC12：记 sessionId/destination，禁打令牌内容；异常消息含 destination 与 traceId 供排障锚定
        log.warn(
                "哨兵越面订阅被拒：sessionId={}，destination={}，traceId={}",
                accessor.getSessionId(),
                destination,
                MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
        throw new MessagingException(
                "大屏匿名令牌仅可订阅物联网主题白名单：" + destination + "，traceId=" + MDC.get(IotMessagingConstants.TRACE_ID_MDC_KEY));
    }

    /**
     * 哨兵订阅白名单判定：三主题族前缀匹配（telemetry/alarm/device-status——{@code {wardId}}
     * 尾段任意，段级核验待 W-74 收口）+ 全院摘要主题精确匹配（无尾段，多一层即越面）。
     *
     * @param destination 订阅目的地，非空（null 在调用前已分流为越面拒）
     * @return true=白名单内（放行）；false=越面（拒）
     */
    private static boolean withinWhitelist(String destination) {
        return destination.startsWith(IotMessagingConstants.TOPIC_TELEMETRY_PREFIX)
                || destination.startsWith(IotMessagingConstants.TOPIC_ALARM_PREFIX)
                || destination.startsWith(IotMessagingConstants.TOPIC_DEVICE_STATUS_PREFIX)
                || destination.equals(IotMessagingConstants.TOPIC_DASHBOARD_GLOBAL);
    }
}
