package com.fuyun.system.service;

import com.fuyun.system.record.SessionData;
import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenPair;

/**
 * 令牌服务契约（D-2 轻量 HMAC 令牌 + Redis 会话，BRIEF-PR3-01 §1）。
 *
 * <p>落 fuyun-system 不下沉 common（简报 §1.5 裁决）：令牌格式与会话结构是 M01 的领域契约。
 * 令牌线格式：{@code Base64Url(payloadJson) + "." + Base64Url(HMAC-SHA256(payloadJsonBytes, secret))}
 * 两段式（非 JWT 三段）；会话键 {@code fy:system:session:{sid}}，值 = SessionData JSON（String 序列化）。
 *
 * <p>校验链（任一失败即 401，错误码区分见实现）：两段格式 → 重算签名常量时间比较 → exp 未过 →
 * typ 严格匹配 → Redis 会话键存在；校验成功执行滑动续期（会话 TTL 重置为 access TTL）。
 */
public interface ITokenService {

    /**
     * 为登录会话签发令牌对（access + refresh，同 sid）。
     *
     * <p>副作用：会话状态以 access TTL 写入 Redis（键 fy:system:session:{sid}）；sid 由本方法生成。
     *
     * @param user 登录会话输入（认证成功后的用户身份与角色摘要），非空
     * @return 令牌对，非空；accessToken/refreshToken 共享同一 sid
     */
    TokenPair issue(SessionUser user);

    /**
     * 校验原始令牌并返回会话状态（认证拦截器与刷新端点的共用入口）。
     *
     * <p>成功副作用：滑动续期——会话 TTL 重置为 access TTL（廉价写，不做阈值判断）。
     *
     * @param rawToken     原始令牌串（Bearer 方案后的值），非空
     * @param expectedType 期望令牌类型，非空；access/refresh，typ 不符按无效令牌拒绝（防跨类型复用）
     * @return 会话状态，非空
     * @throws com.fuyun.common.exception.BizException 校验链任一环节失败：
     *                                                 SYS-1003（格式/签名/typ/会话不存在）或
     *                                                 SYS-1004（exp 已过），HTTP 401
     */
    SessionData verify(String rawToken, String expectedType);

    /**
     * 登出/踢出：删除会话键，access 与 refresh 同 sid 同时失效。
     *
     * <p>幂等：键不存在时静默完成（登出重入安全）。
     *
     * @param sid 会话标识（令牌 claims 中的 sid），非空
     */
    void evict(String sid);
}
