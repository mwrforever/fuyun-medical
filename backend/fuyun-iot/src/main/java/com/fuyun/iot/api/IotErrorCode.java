package com.fuyun.iot.api;

import com.fuyun.common.exception.ErrorCode;

/**
 * M14 医疗设备物联网平台模块错误码枚举（IOT-xxxx，backend 宪法 A.3-4）。
 *
 * <p>落 api 包为宪法 B.1 明文（错误码枚举属对外契约）；实现 common {@link ErrorCode} 契约，
 * 全项目编码唯一（当前 IOT- 前缀无其他占用）。业务异常抛
 * {@code BizException(IotErrorCode.XXX, HttpStatus, message)}，由全局渲染器输出
 * RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止"全 200 + 错误码"。
 *
 * <p>B4.2 仅登记（兜底通道鉴权失败码，供 B4.3 HTTP 兜底端点使用）；后续码段随功能实装扩充。
 */
public enum IotErrorCode implements ErrorCode {

    /** 兜底通道鉴权失败（401；X-Iot-Fallback-Token 头缺失或不匹配，独立鉴权不走 M01 令牌） */
    FALLBACK_AUTH_FAILED("IOT-1001");

    /** 错误码字符串，格式 {@code <模块助记>-<4位数字>} */
    private final String code;

    IotErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取业务错误码。
     *
     * @return 错误码字符串（如 IOT-1001），非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @Override
    public String getCode() {
        return code;
    }
}
