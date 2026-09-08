package com.fuyun.common.web;

import com.fuyun.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 全局异常渲染器：全部失败响应统一输出 RFC 9457 ProblemDetail（backend 宪法 A.3-2）。
 *
 * <p>继承 {@link ResponseEntityExceptionHandler}：参数校验失败、404/405 等标准 MVC 异常由父类按
 * ProblemDetail 处理（需 spring.mvc.problemdetails.enabled=true）；业务异常走
 * {@link #handleBizException(BizException)}，未知异常走 {@link #handleUnexpected(Exception)} 兜底。
 * 各 controller 禁止自行兜底渲染异常（backend 宪法 A.3-4）。
 *
 * <p>线程安全：无状态单例；traceId 按请求从 MDC 读取，不持有任何请求级状态。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** traceId 的 MDC 键：与 TraceIdFilter 默认键、fuyun.trace.mdc-key 默认值保持一致 */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /** 未知异常兜底 detail 文案：通用措辞，禁止泄漏堆栈、类名等内部信息 */
    private static final String INTERNAL_ERROR_DETAIL = "系统繁忙，请稍后重试";

    /**
     * 业务异常渲染：按异常自带的 HTTP 状态与错误码输出 ProblemDetail。
     *
     * <p>执行流程：取异常携带的 HttpStatus 构建 ProblemDetail → 追加 errorCode / traceId 扩展属性。
     *
     * @param ex 业务异常，非空；由 service 层抛出，其错误码与状态决定响应体内容
     * @return ProblemDetail；状态与异常一致，properties.errorCode 为业务错误码，
     *         properties.traceId 为当前链路追踪 ID（非 HTTP 请求线程下可能为 null）
     */
    @ExceptionHandler(BizException.class)
    public ProblemDetail handleBizException(BizException ex) {
        // 业务失败属预期场景，warn 级按错误码聚合便于运营排障；message 为业务文案，不含敏感值
        log.warn(
                "业务异常：errorCode={}，httpStatus={}，message={}",
                ex.getErrorCode().getCode(),
                ex.getHttpStatus().value(),
                ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(ex.getHttpStatus(), ex.getMessage());
        body.setProperty("errorCode", ex.getErrorCode().getCode());
        body.setProperty("traceId", MDC.get(TRACE_ID_MDC_KEY));
        return body;
    }

    /**
     * 未知异常兜底：统一返回 500，detail 使用通用文案防止内部信息泄漏。
     *
     * <p>触发原因：未被更具体 handler 匹配的任意异常（空指针、下游调用失败等）。
     * 处理策略：error 级记录完整堆栈，运维凭 traceId 关联定位，禁止向客户端透出细节。
     *
     * @param ex 未预期异常，非空
     * @return ProblemDetail；状态 500，properties.traceId 为当前链路追踪 ID（可能为 null）
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("系统未处理异常：traceId={}", MDC.get(TRACE_ID_MDC_KEY), ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL);
        body.setProperty("traceId", MDC.get(TRACE_ID_MDC_KEY));
        return body;
    }
}
