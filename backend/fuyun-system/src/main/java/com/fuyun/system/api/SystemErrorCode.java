package com.fuyun.system.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M01 系统与权限管理模块错误码枚举（SYS-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（当前 SYS- 前缀无其他占用）。业务异常抛
 * {@code BizException(SystemErrorCode.XXX, HttpStatus, message)}，由全局渲染器输出
 * RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止"全 200 + 错误码"。
 *
 * <p>防枚举口径：SYS-1001 登录名不存在与密码错误共用同一文案（安全红线 BRIEF-PR3-01 §8-11）。
 */
public enum SystemErrorCode implements ErrorCode {

    /** 登录名或密码错误（401；防用户枚举，两种失败同文案） */
    LOGIN_NAME_OR_PASSWORD_WRONG("SYS-1001"),

    /** 账号已锁定（401；文案须含解锁时间） */
    ACCOUNT_LOCKED("SYS-1002"),

    /** 令牌缺失或无效（401；缺 Authorization 头/非 Bearer/签名无效/typ 不符/会话不存在） */
    TOKEN_MISSING_OR_INVALID("SYS-1003"),

    /** 令牌已过期（401；exp 已过） */
    TOKEN_EXPIRED("SYS-1004"),

    /** 刷新令牌无效（401；typ 错/签名错/会话不存在） */
    REFRESH_TOKEN_INVALID("SYS-1005"),

    /** 账号已停用（403） */
    ACCOUNT_DISABLED("SYS-1006"),

    /** 字典类型不存在（404） */
    DICT_TYPE_NOT_FOUND("SYS-1011"),

    /** 字典版本不存在（404） */
    DICT_VERSION_NOT_FOUND("SYS-1012"),

    /** 字典版本状态不允许发布（409；仅 DRAFT 可发布） */
    DICT_VERSION_NOT_PUBLISHABLE("SYS-1013"),

    /** 字典类型编码已存在（409） */
    DICT_TYPE_CODE_EXISTS("SYS-1014"),

    /** 执业授权记录不存在（404）：withdraw/query 定位失败 */
    PRACTICE_GRANT_NOT_FOUND("SYS-1021"),

    /** 同一员工同一授权类型已存在生效行（409）：重复登记冲突 */
    PRACTICE_GRANT_DUPLICATE("SYS-1022"),

    /**
     * 枚举 code 值域非法（400；通用段 1031+，BE-C3-05/A.3-3 双层错误模型收口）：
     * 全部枚举 fromCode 未知 code 统一抛出（外部入参转枚举失败按非法请求处置，禁散落裸
     * IllegalArgumentException）。
     */
    ENUM_VALUE_INVALID("SYS-1031"),

    /** 大屏匿名令牌越权访问只读看板白名单外端点或病区不匹配（403；PR-4C W-39 哨兵 REST 限行） */
    SENTINEL_ACCESS_DENIED("SYS-1032");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    SystemErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 SYS-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
