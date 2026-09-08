package com.fuyun.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * traceId 过滤器单元测试：基于 Spring mock 请求/响应直接驱动过滤器，覆盖生成、透传、清理与开关四类场景。
 */
class TraceIdFilterTest {

    /** 与生产配置一致的 MDC 键 */
    private static final String MDC_KEY = "traceId";

    /** 标准 UUID 形态（小写、5 段、连字符分隔），用于断言"缺失时生成"场景 */
    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    @DisplayName("请求头缺失 X-Trace-Id 时生成 UUID 形态 traceId，写入 MDC 并回写响应头")
    void generatesTraceIdAndWritesBackResponseHeaderWhenHeaderMissing() throws ServletException, IOException {
        TraceIdFilter filter = new TraceIdFilter(MDC_KEY, true);
        // 链内采样：过滤器 finally 会清理 MDC，只能在下游链执行期间捕获当次值（FilterChain.doFilter 为 2 参，链尾无需继续传递）
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get(MDC_KEY)));

        assertThat(mdcDuringChain.get()).isNotBlank().matches(UUID_PATTERN);
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isEqualTo(mdcDuringChain.get());
    }

    @Test
    @DisplayName("请求头携带 X-Trace-Id 时透传原值，不重新生成")
    void reusesIncomingTraceIdWhenHeaderPresent() throws ServletException, IOException {
        TraceIdFilter filter = new TraceIdFilter(MDC_KEY, true);
        request.addHeader(TraceIdFilter.TRACE_HEADER, "trace-from-gateway");
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get(MDC_KEY)));

        assertThat(mdcDuringChain.get()).isEqualTo("trace-from-gateway");
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isEqualTo("trace-from-gateway");
    }

    @Test
    @DisplayName("请求结束后 MDC 中 traceId 被清理，防止线程池复用串号")
    void clearsMdcAfterRequestCompleted() throws ServletException, IOException {
        TraceIdFilter filter = new TraceIdFilter(MDC_KEY, true);

        filter.doFilter(request, response, (req, res) -> {
            // 空链尾：仅验证 finally 清理逻辑，无下游处理
        });

        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("回写开关关闭时不写 X-Trace-Id 响应头，MDC 链路照常建立")
    void skipsResponseHeaderWhenSwitchDisabled() throws ServletException, IOException {
        TraceIdFilter filter = new TraceIdFilter(MDC_KEY, false);
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcDuringChain.set(MDC.get(MDC_KEY)));

        assertThat(mdcDuringChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.TRACE_HEADER)).isNull();
    }
}
