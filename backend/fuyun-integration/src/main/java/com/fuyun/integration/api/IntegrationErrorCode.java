package com.fuyun.integration.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M20 集成平台模块错误码枚举（INT-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（当前 INT- 前缀无其他占用）。业务异常抛
 * {@code BizException(IntegrationErrorCode.XXX, HttpStatus, message)}，由全局渲染器输出
 * RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止「全 200 + 错误码」。
 *
 * <p>段位约定：1001-1005 死信管理；1011-1012 主数据分发治理。后续码段随 FU 实装扩充。
 */
public enum IntegrationErrorCode implements ErrorCode {

    /** 死信不存在（404；id 未命中台账） */
    DEAD_LETTER_NOT_FOUND("INT-1001"),

    /** 死信当前状态不允许该操作（409；重放仅 PENDING，关闭仅 PENDING，CLOSED 为终态） */
    DEAD_LETTER_STATUS_NOT_ACTIONABLE("INT-1002"),

    /** 死信重推次数已达上限（409；上限 DEAD_LETTER_REPLAY_MAX_COUNT，控制器拍板值） */
    DEAD_LETTER_REPLAY_LIMIT_EXCEEDED("INT-1003"),

    /** 死信不可重放（409；信封不合规无路由键/来源队列缺失或已下线，重放必然不可路由） */
    DEAD_LETTER_NOT_REPLAYABLE("INT-1004"),

    /** 死信重放投递失败（500；broker 不可达等，已回到待处理并累加重放次数） */
    DEAD_LETTER_REPLAY_DELIVERY_FAILED("INT-1005"),

    /** 主数据订阅记录不存在（404；注销未命中台账） */
    MDM_SUBSCRIPTION_NOT_FOUND("INT-1011"),

    /** 未知主数据主题（400；合法主题见 MdmConstants.TOPICS） */
    MDM_TOPIC_UNKNOWN("INT-1012");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    IntegrationErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 INT-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
