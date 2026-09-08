package com.fuyun.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常基座：各模块业务失败的统一父类。
 *
 * <p>继承 {@link RuntimeException}（backend 宪法 A.1-6：禁止 checked 业务异常，与事务默认回滚语义互锁）；
 * 携带错误码与 HTTP 状态，由全局渲染器统一输出为 RFC 9457 ProblemDetail。
 * 业务模块应定义自身业务异常继承本类（或直接抛出本类），禁止散落裸 RuntimeException。
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码，来源为模块错误码枚举；由全局渲染器输出至 ProblemDetail.properties.errorCode */
    private final ErrorCode errorCode;

    /** HTTP 状态表达传输语义（400/401/403/404/409 等），与业务错误码双层分离（backend 宪法 A.3-3） */
    private final HttpStatus httpStatus;

    /**
     * 全参构造器：业务侧需要自定义可读错误消息的场景。
     *
     * @param errorCode  业务错误码，非空；来源：模块错误码枚举
     * @param httpStatus HTTP 状态，非空；来源：controller/service 层的传输语义定义
     * @param message    错误消息（业务可读），非空；禁止携带密码、token 等敏感值
     */
    public BizException(ErrorCode errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 便捷构造器：无需专属消息时使用，message 默认取错误码自身。
     *
     * @param errorCode  业务错误码，非空
     * @param httpStatus HTTP 状态，非空
     */
    public BizException(ErrorCode errorCode, HttpStatus httpStatus) {
        this(errorCode, httpStatus, errorCode.getCode());
    }
}
