package com.fuyun.system.api;

/**
 * 访问令牌布尔校验契约（PR-4 B4.3 跨模块小改，BRIEF-PR4-01 §4）：M01 令牌校验能力对无
 * ProblemDetail 出口场景的最小暴露面。
 *
 * <p>落 api 包为宪法 B.1 明文（跨模块契约唯一出口）：消费方为 fuyun-iot 的 /ws/iot STOMP
 * CONNECT 帧级鉴权（PR-5 Finding 1 迁移——浏览器原生 WebSocket 无法携带自定义 HTTP 头，令牌
 * 承载于建连后的 CONNECT 帧）——帧级拒绝以 MessagingException 触发 ERROR 帧 + 连接关闭，无
 * 全局异常渲染器出口；MQ 线程同理无 HTTP 语义。两场景布尔语义足够，故独立于
 * {@link com.fuyun.system.service.ITokenService}（其 verify 抛 BizException 携带错误码细分，
 * 服务 HTTP 401 渲染）单列契约，两者互不继承防契约耦合；实现类 TokenServiceImpl 同一实例
 * 双接口暴露（SystemWebConfig 装配）。
 *
 * <p>失败不区分原因（防枚举）：格式/签名/过期/typ 不符/会话不存在统一 false——调用方禁止
 * 据返回值推断失败环节并向客户端透出细分差异。
 */
public interface TokenVerifier {

    /**
     * 校验访问令牌全链并返回布尔结果（WebSocket 握手等无 ProblemDetail 出口场景的鉴权入口）。
     *
     * <p>校验链与 ITokenService.verify 完全同源：两段格式 → 常量时间签名比对 → exp 未过 →
     * typ=access 严格匹配 → Redis 会话存在；校验成功执行滑动续期副作用（与 HTTP 认证口径
     * 一致，会话 TTL 重置为 access TTL）。
     *
     * @param rawToken 访问令牌原文（Bearer 方案后的值），允许为空或空白（握手头缺失等场景，
     *                 一律 false 不抛异常）；来源：调用方从请求头提取
     * @return true=令牌全链有效；false=校验链任一环节失败（不区分原因、不抛异常）
     */
    boolean verifyAccessToken(String rawToken);
}
