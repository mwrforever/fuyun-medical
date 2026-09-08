package com.fuyun.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuyun.common.exception.BizException;
import com.fuyun.common.exception.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * 全局异常渲染单元测试：直接调用 handler 方法断言 ProblemDetail 的状态码与扩展属性（不打走 MVC 栈）。
 */
class GlobalExceptionHandlerTest {

    /** 与 TraceIdFilter 默认键、fuyun.trace.mdc-key 默认值保持一致的 MDC 键 */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /** 测试专用错误码枚举：模拟各业务模块 api/ 包下的真实错误码枚举实现 */
    private enum TestErrorCode implements ErrorCode {
        PATIENT_NOT_FOUND("PATI-4041");

        private final String code;

        TestErrorCode(String code) {
            this.code = code;
        }

        @Override
        public String getCode() {
            return code;
        }
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void cleanUp() {
        // 清理本测试写入的 MDC，避免影响同线程后续测试
        MDC.remove(TRACE_ID_MDC_KEY);
    }

    @Test
    @DisplayName("业务异常渲染出的 ProblemDetail 状态码与异常一致，errorCode 与 traceId 正确放入 properties")
    void rendersBizExceptionAsProblemDetailWithErrorCodeAndTraceId() {
        String traceId = "trace-" + UUID.randomUUID();
        // 预先造 traceId 锚点：模拟请求已过 TraceIdFilter
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        BizException exception = new BizException(TestErrorCode.PATIENT_NOT_FOUND, HttpStatus.NOT_FOUND, "患者档案不存在");

        ProblemDetail body = handler.handleBizException(exception);

        assertThat(body.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(body.getDetail()).isEqualTo("患者档案不存在");
        assertThat(body.getProperties()).containsEntry("errorCode", "PATI-4041").containsEntry("traceId", traceId);
    }

    @Test
    @DisplayName("未知异常兜底返回 500，detail 为通用文案不泄漏内部信息，properties 仅带 traceId")
    void rendersUnexpectedExceptionAs500WithGenericDetailAndTraceIdOnly() {
        MDC.put(TRACE_ID_MDC_KEY, "trace-anchor");

        ProblemDetail body = handler.handleUnexpected(new IllegalStateException("内部堆栈细节"));

        assertThat(body.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(body.getDetail()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(body.getProperties()).containsEntry("traceId", "trace-anchor");
        assertThat(body.getProperties()).doesNotContainKey("errorCode");
    }
}
