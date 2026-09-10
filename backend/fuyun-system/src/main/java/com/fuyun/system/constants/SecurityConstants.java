package com.fuyun.system.constants;

import java.time.Duration;

/**
 * 认证安全常量（D-2 令牌方案，BRIEF-PR3-01 §1）：会话键、令牌 claims 短键、令牌类型、
 * Bearer 方案与登录锁定策略的运行期不变量集中地（backend 宪法 A.2-6 禁魔法值散落）。
 *
 * <p>HMAC 密钥不在此列：密钥属敏感配置，仅经 SecurityProperties（fuyun.security.* ←
 * FUYUN_SECURITY_TOKEN_HMAC_SECRET 环境变量）注入，任何常量/代码/yml 承载明文密钥即红线。
 */
public final class SecurityConstants {

    /** Redis 会话键前缀：fy:system:session:{sid} 冒号分层（A.5-1 键规范），sid=会话 UUID */
    public static final String SESSION_KEY_PREFIX = "fy:system:session:";

    // ---------------------------------------------------------------- 令牌 payload 线格式短键（冻结）
    // record TokenClaims 组件名与下列常量一一对应；线格式经 TokenServiceImplTest 冻结断言，
    // 任何一侧改名必须同步（防 JWT claims 集合语义误用，本方案为短键自定义两段式）

    /** 用户 ID（雪花 ID 十进制字符串） */
    public static final String CLAIM_UID = "uid";

    /** 员工 ID（可 null：系统/接口账号无员工） */
    public static final String CLAIM_EID = "eid";

    /** 主归属机构 ID（可 null：P0 种子不建 org 行） */
    public static final String CLAIM_OID = "oid";

    /** 会话标识 UUID（= Redis 会话键尾段） */
    public static final String CLAIM_SID = "sid";

    /** 令牌类型：access/refresh */
    public static final String CLAIM_TYP = "typ";

    /** 过期时刻 epoch 毫秒 */
    public static final String CLAIM_EXP = "exp";

    /** 令牌类型值：访问令牌 */
    public static final String TOKEN_TYPE_ACCESS = "access";

    /** 令牌类型值：刷新令牌 */
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    /** 授权请求头名称 */
    public static final String AUTH_HEADER = "Authorization";

    /** Bearer 方案前缀（含尾随空格，拼接令牌值） */
    public static final String BEARER_PREFIX = "Bearer ";

    /** 登录失败锁定阈值：连续失败达 5 次置锁定（M01 Spec §5 用户状态机） */
    public static final int LOGIN_FAIL_LOCK_THRESHOLD = 5;

    /** 锁定时长：锁定 30 分钟后自动到期恢复（P0 无手动解锁端点） */
    public static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(30);

    /** traceId 的 MDC 键名：与 TraceIdFilter 默认键、fuyun.trace.mdc-key 默认值保持一致 */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** 纯常量类，禁止实例化（backend 宪法 A.2-6） */
    private SecurityConstants() {}
}
