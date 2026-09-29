package com.fuyun.ward.api;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fuyun.common.exception.ErrorCode;

/**
 * M16 智慧病房应用域错误码枚举（WD-xxxx，A.3-4；前缀查证：依模块名惯例取 WD，全仓 grep 零命中——
 * 2026-09-26 核验）。业务异常统一 {@code BizException(WardErrorCode.X, HttpStatus, message)}
 * 经全局渲染输出 RFC 9457 ProblemDetail（properties.errorCode/traceId），禁止"全 200 + 错误码"。
 * WD-1001 起连续无重号，后续新增一律接续顺延（EX-19 收口接续新增 WD-1007/WD-1008，2026-09-29）。
 */
public enum WardErrorCode implements ErrorCode {

    /** 呼叫不存在（404；call_no 无命中） */
    CALL_NOT_FOUND("WD-1001"),
    /** 呼叫状态不允许该操作（409；状态机违例：终态再迁移、非法侧支路径等） */
    CALL_STATE_NOT_ALLOWED("WD-1002"),
    /** 呼叫路由未配置（409；ward_id+call_type 无生效路由规则，转接触发时即拒） */
    CALL_ROUTING_NOT_CONFIGURED("WD-1003"),
    /** 冷链档案不存在（404；archive_no 无命中） */
    COLD_CHAIN_NOT_FOUND("WD-1004"),
    /** 冷链处置记录非法（400；ALARM_HANDLE 缺 alarm_ref 或双人核对缺第二人） */
    COLD_CHAIN_RECORD_INVALID("WD-1005"),
    /** 冷链状态不允许该操作（409；状态机违例） */
    COLD_CHAIN_STATE_NOT_ALLOWED("WD-1006"),
    /** 枚举 code 词表外（400；fromCode 无命中——外部输入/报文 code 不在词表值域，BE-C3-05/EX-19 收口 A 类，六词表枚举共用） */
    ENUM_CODE_INVALID("WD-1007"),
    /** 设备源呼叫禁手工入口（400；source=IOT 须走 iot 事件消费落行，防双通道重复落行，EX-19 收口 B 类） */
    CALL_SOURCE_IOT_FORBIDDEN("WD-1008");

    /** 码值（如 WD-1001），A.2-7 code↔enum 双向映射之 code 侧 */
    private final String code;

    WardErrorCode(String code) {
        this.code = code;
    }

    /**
     * 取错误码字符串。
     *
     * @return 码值，非空；经全局渲染输出至 ProblemDetail.properties.errorCode
     */
    @JsonValue
    @Override
    public String getCode() {
        return code;
    }
}
