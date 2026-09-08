package com.fuyun.app.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuyun.common.context.TraceIdFilter;
import com.fuyun.common.exception.BizException;
import com.fuyun.common.exception.ErrorCode;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局异常渲染器装配注册测试（PR #4 审查 Finding 1）。
 *
 * <p>业务意图：证明 {@code com.fuyun.common.web.GlobalExceptionHandler} 经生产装配路径
 * （TraceIdConfig 的 @Import）真实进入 MVC 上下文——common 包不在 @SpringBootApplication
 * 默认扫描范围内，漏装配即统一异常契约（ProblemDetail + errorCode/traceId）静默失效。
 *
 * <p>采用 @WebMvcTest 切片：仅装配 Web 层，不触发 Flyway/数据源等基础设施；
 * 经 @Import(TraceIdConfig.class) 复现生产装配链（app 配置类 → 全局渲染器 → traceId 过滤器）。
 */
@WebMvcTest(ProbeController.class)
@Import(TraceIdConfig.class)
class GlobalExceptionHandlerRegistrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("探针接口抛业务异常时响应为 RFC 9457 ProblemDetail，状态码/错误码/traceId 契约完整")
    void rendersBizExceptionAsProblemDetailWithFullContractFields() throws Exception {
        mockMvc.perform(get("/probe/biz-exception"))
                // HTTP 状态取业务异常自带的传输语义（409），而非兜底 500
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("订单状态不允许该操作"))
                // errorCode 与 traceId 为契约必带扩展属性：缺失即证明渲染器未装配
                .andExpect(jsonPath("$.errorCode").value("TST-1001"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                // 响应头回写 X-Trace-Id（TraceIdFilter 同经 TraceIdConfig 装配生效的旁证）
                .andExpect(header().string(TraceIdFilter.TRACE_HEADER, Matchers.not(Matchers.emptyOrNullString())));
    }
}

/** 测试探针控制器：唯一职责是抛出业务异常驱动全局渲染路径，仅存在于测试类路径 */
@RestController
class ProbeController {

    /** 探针端点：故意抛出携带错误码与 409 状态的业务异常，供断言统一异常契约 */
    @GetMapping("/probe/biz-exception")
    public String triggerBizException() {
        throw new BizException(ProbeErrorCode.ORDER_STATE_CONFLICT, HttpStatus.CONFLICT, "订单状态不允许该操作");
    }
}

/** 测试专用错误码枚举：模拟业务模块 api/ 包错误码实现（测试类路径，不进生产代码） */
enum ProbeErrorCode implements ErrorCode {
    ORDER_STATE_CONFLICT("TST-1001");

    private final String code;

    ProbeErrorCode(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
