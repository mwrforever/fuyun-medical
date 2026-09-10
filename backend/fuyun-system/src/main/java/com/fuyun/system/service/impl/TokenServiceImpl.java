package com.fuyun.system.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.common.exception.BizException;
import com.fuyun.system.api.SystemErrorCode;
import com.fuyun.system.constants.SecurityConstants;
import com.fuyun.system.properties.SecurityProperties;
import com.fuyun.system.record.RefreshedAccess;
import com.fuyun.system.record.SessionData;
import com.fuyun.system.record.SessionUser;
import com.fuyun.system.record.TokenClaims;
import com.fuyun.system.record.TokenPair;
import com.fuyun.system.service.ITokenService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/**
 * HMAC 令牌服务实现（D-2 方案核心路径，BRIEF-PR3-01 §1 全量规格）。
 *
 * <p>线格式：{@code Base64Url(payloadJson) + "." + Base64Url(HMAC-SHA256(payload, secret))} 两段式，
 * 签名经 JDK {@code Mac}（HmacSHA256）+ {@link MessageDigest#isEqual} 常量时间比较（防时序攻击），
 * 不引 JWT/spring-security 全家桶。会话经 StringRedisTemplate 承载（Key/Value 均 String JSON，
 * 禁 JDK 序列化）；会话键必有 TTL 且校验成功滑动续期（access TTL）。
 *
 * <p>线程安全：无状态单例——Mac 每次调用新建实例（Mac 非线程安全，禁做字段复用），
 * 全部状态取自不可变 {@link SecurityProperties} 与注入的线程安全 Bean。
 */
@Slf4j
public class TokenServiceImpl implements ITokenService {

    /** HMAC 算法名：JDK 标准算法，跨实例可复算（密钥轮换属 P1 治理项，P0 单密钥静态注入） */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 安全配置（密钥与双 TTL），构造期注入 */
    private final SecurityProperties properties;

    /** Redis 客户端：会话键 String 读写与 TTL 管理 */
    private final StringRedisTemplate redisTemplate;

    /** JSON 转换器：claims 与会话状态的紧凑序列化（注入应用级 ObjectMapper） */
    private final ObjectMapper objectMapper;

    /** 时钟：签发 exp 计算与过期判定的唯一时间源（测试注入固定时钟） */
    private final Clock clock;

    /** HMAC 密钥字节：UTF-8 编码一次性派生（SecurityProperties 启动期已保证非空且 ≥32 字符） */
    private final byte[] secretBytes;

    /**
     * 全参构造器（装配归 fuyun-app 配置类 @Import，backend 宪法 B.1；本类不加 @Component）。
     *
     * @param properties    安全配置，非空；来源：fuyun.security.* 经构造器绑定
     * @param redisTemplate String 序列化 Redis 模板，非空
     * @param objectMapper  JSON 转换器，非空
     * @param clock         时间源，非空；生产取 Clock.systemUTC()，测试注入固定时钟
     */
    public TokenServiceImpl(
            SecurityProperties properties, StringRedisTemplate redisTemplate, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secretBytes = properties.tokenHmacSecret().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 签发令牌对：会话先行落 Redis（带 TTL，禁无过期键），access 与 refresh 共享同一新 sid。
     *
     * @param user 登录会话输入，非空；uid/eid/oid 由此取值（null 保留为 JSON null）
     * @return 令牌对，非空
     * @throws IllegalStateException 会话 JSON 序列化失败（系统级故障，交全局渲染器兜底 500）
     */
    @Override
    public TokenPair issue(SessionUser user) {
        String sid = UUID.randomUUID().toString();
        // 会话先行落库：保证令牌签出即可用（sid→会话键存在），避免"签发成功但会话缺失"的瞬时 401 窗口
        SessionData session = new SessionData(
                user.userId(), user.loginName(), user.displayName(), user.employeeId(), user.orgId(), user.roles());
        String sessionJson;
        try {
            sessionJson = objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            log.error("会话 JSON 序列化失败：userId={}", user.userId(), e);
            throw new IllegalStateException("登录会话序列化失败", e);
        }
        // 会话键必有 TTL（A.5-1 红线）：初值取 access TTL，此后随校验滑动续期
        redisTemplate.opsForValue().set(sessionKey(sid), sessionJson, properties.accessTokenTtl());

        // 短键 claims：uid/eid/oid 统一十进制字符串（Long 越界防线，JSON null 显式承载"无"语义）
        TokenClaims accessClaims = new TokenClaims(
                String.valueOf(user.userId()),
                longToClaimsValue(user.employeeId()),
                longToClaimsValue(user.orgId()),
                sid,
                SecurityConstants.TOKEN_TYPE_ACCESS,
                clock.millis() + properties.accessTokenTtl().toMillis());
        TokenClaims refreshClaims = new TokenClaims(
                accessClaims.uid(),
                accessClaims.eid(),
                accessClaims.oid(),
                sid,
                SecurityConstants.TOKEN_TYPE_REFRESH,
                clock.millis() + properties.refreshTokenTtl().toMillis());
        log.info(
                "签发令牌对：userId={}，sid={}，accessTtl={}s",
                user.userId(),
                sid,
                properties.accessTokenTtl().toSeconds());
        return new TokenPair(sign(accessClaims), sign(refreshClaims));
    }

    /**
     * 校验链：两段格式 → 常量时间签名比对 → exp 未过 → typ 严格匹配 → 会话键存在 → 滑动续期。
     *
     * @param rawToken     原始令牌串，非空
     * @param expectedType 期望令牌类型，非空
     * @return 会话状态，非空
     * @throws BizException SYS-1004（exp 已过）或 SYS-1003（其余全部失败形态），HTTP 401
     */
    @Override
    public SessionData verify(String rawToken, String expectedType) {
        return verifyInternal(rawToken, expectedType).session();
    }

    /**
     * 刷新换发：typ=refresh 校验链通过后，以原 sid 签发新 access 令牌（会话不重建、refresh 不轮换）。
     *
     * <p>失败统一映射 SYS-1005/401（刷新端点错误码口径，BRIEF-PR3-01 §1.3），不透出
     * SYS-1003/1004 细分（refresh 令牌对调用方仅为"有效/无效"二元语义）。
     *
     * @param rawRefreshToken 刷新令牌原文，非空
     * @return 换发结果（新 access + 会话状态），非空
     * @throws BizException SYS-1005，HTTP 401
     */
    @Override
    public RefreshedAccess refreshAccessToken(String rawRefreshToken) {
        VerifiedToken verified;
        try {
            verified = verifyInternal(rawRefreshToken, SecurityConstants.TOKEN_TYPE_REFRESH);
        } catch (BizException e) {
            // typ 错/签名错/过期/会话不存在统一按"刷新令牌无效"拒绝（防刷新端点错误细分探测面）
            throw new BizException(SystemErrorCode.REFRESH_TOKEN_INVALID, HttpStatus.UNAUTHORIZED, "刷新令牌无效，请重新登录");
        }
        TokenClaims claims = verified.claims();
        // 同 sid 新 access：会话键已由校验链滑动续期，此处仅签发不写 Redis
        TokenClaims accessClaims = new TokenClaims(
                claims.uid(),
                claims.eid(),
                claims.oid(),
                claims.sid(),
                SecurityConstants.TOKEN_TYPE_ACCESS,
                clock.millis() + properties.accessTokenTtl().toMillis());
        log.info("刷新换发 access 令牌：sid={}，userId={}", claims.sid(), claims.uid());
        return new RefreshedAccess(sign(accessClaims), verified.session());
    }

    /**
     * 登出：typ=access 校验链通过后按令牌内 sid 删除会话键（access 与 refresh 同时失效）。
     *
     * @param rawToken 访问令牌原文，非空
     * @throws BizException SYS-1003/SYS-1004，HTTP 401（校验链失败，防伪造令牌触发删除）
     */
    @Override
    public void logout(String rawToken) {
        String sid = verifyInternal(rawToken, SecurityConstants.TOKEN_TYPE_ACCESS)
                .claims()
                .sid();
        evict(sid);
    }

    /**
     * 删除会话键：登出与改密/停用踢出共用入口（B3.2 AuthService 接入）。
     *
     * @param sid 会话标识，非空
     */
    @Override
    public void evict(String sid) {
        Boolean deleted = redisTemplate.delete(sessionKey(sid));
        log.info("删除登录会话：sid={}，删除前存在={}", sid, Boolean.TRUE.equals(deleted));
    }

    /**
     * 校验链内部执行体：两段格式 → 常量时间签名比对 → exp 未过 → typ 严格匹配 → 会话键存在 →
     * 滑动续期；同时返回 claims（verify/刷新/登出对 sid 的差异化取用）。
     *
     * @param rawToken     原始令牌串，非空
     * @param expectedType 期望令牌类型，非空
     * @return claims 与会话状态的已校验二元组，非空
     * @throws BizException SYS-1004（exp 已过）或 SYS-1003（其余全部失败形态），HTTP 401
     */
    private VerifiedToken verifyInternal(String rawToken, String expectedType) {
        // 1. 两段格式（split 限 -1 检出尾随空段）；非法 base64url 一律归"无效令牌"
        String[] parts = rawToken.split("\\.", -1);
        if (parts.length != 2) {
            throw invalidToken("令牌格式非法");
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw invalidToken("令牌编码非法");
        }
        // 2. 重算签名 + 常量时间比较（防时序侧信道逐字节猜测签名）
        if (!MessageDigest.isEqual(hmac(payload), signature)) {
            throw invalidToken("令牌签名无效");
        }
        TokenClaims claims;
        try {
            claims = objectMapper.readValue(payload, TokenClaims.class);
        } catch (IOException e) {
            throw invalidToken("令牌载荷非法");
        }
        // 3. exp 未过（exp=过期时刻，等于该时刻亦判过期）
        if (claims.exp() <= clock.millis()) {
            throw new BizException(SystemErrorCode.TOKEN_EXPIRED, HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        // 4. typ 严格匹配（refresh 令牌不得当 access 使用，反之亦然——防跨类型复用）
        if (!expectedType.equals(claims.typ())) {
            throw invalidToken("令牌类型不符");
        }
        // 5. 会话键存在（登出/踢出已删键的令牌即行失效——"删除即全端失效"语义落点）
        String key = sessionKey(claims.sid());
        String sessionJson = redisTemplate.opsForValue().get(key);
        if (sessionJson == null) {
            throw invalidToken("登录会话不存在");
        }
        SessionData session;
        try {
            session = objectMapper.readValue(sessionJson, SessionData.class);
        } catch (IOException e) {
            // 会话内容损坏按无效令牌处置：拒绝访问并留痕，禁止吞异常放行
            log.error("会话 JSON 反序列化失败：sid={}", claims.sid(), e);
            throw invalidToken("登录会话状态异常");
        }
        // 6. 滑动续期：无条件重置为 access TTL（廉价写，不做阈值判断）；refresh 校验成功同样续满
        redisTemplate.expire(key, properties.accessTokenTtl());
        return new VerifiedToken(claims, session);
    }

    /** 拼接会话键：fy:system:session:{sid}（冒号分层，A.5-1 键规范） */
    private String sessionKey(String sid) {
        return SecurityConstants.SESSION_KEY_PREFIX + sid;
    }

    /** Long → claims 字符串值（null 透传为 JSON null，承载"无员工/无机构"语义） */
    private String longToClaimsValue(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 按线格式签发令牌：紧凑 payload JSON → HMAC → 两段 base64url（无填充）拼接。
     *
     * @param claims 令牌载荷，非空
     * @return 原始令牌串，非空
     * @throws IllegalStateException claims 序列化失败（不可恢复系统故障）
     */
    private String sign(TokenClaims claims) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(claims);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(payload) + "." + encoder.encodeToString(hmac(payload));
        } catch (JsonProcessingException e) {
            log.error("令牌载荷 JSON 序列化失败：typ={}", claims.typ(), e);
            throw new IllegalStateException("令牌载荷序列化失败", e);
        }
    }

    /**
     * 计算 HMAC-SHA256 签名。
     *
     * <p>Mac 实例每次新建：Mac 非线程安全，无状态单例内禁字段复用共享实例。
     *
     * @param data 待签名数据（payload JSON 字节），非空
     * @return 签名字节，非空
     * @throws IllegalStateException JCE 环境异常（HmacSHA256 为 JDK 必备算法，实际不可达）
     */
    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 签名计算失败", e);
        }
    }

    /** 构造"令牌缺失或无效"异常（SYS-1003/401）：detail 为业务可读文案，不含令牌内容 */
    private BizException invalidToken(String detail) {
        return new BizException(SystemErrorCode.TOKEN_MISSING_OR_INVALID, HttpStatus.UNAUTHORIZED, detail);
    }

    /**
     * 已校验令牌二元组值对象：verifyInternal 产物（record 浅不可变，A.1-2）。
     *
     * <p>verify 只取 session（对外契约不变）；刷新/登出需 claims 内的 sid，经本对象内聚传递，
     * 避免在 SessionData 中冗余承载 sid（会话 JSON 线格式保持 B3.1 冻结形态）。
     *
     * @param claims  已通过校验链的令牌载荷，非空
     * @param session 已通过校验链的会话状态，非空
     */
    private record VerifiedToken(TokenClaims claims, SessionData session) {}
}
