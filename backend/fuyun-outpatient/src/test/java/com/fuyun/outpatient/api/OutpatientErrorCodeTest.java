package com.fuyun.outpatient.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 门诊域错误码接续锚（Global Constraints「OP-1001 起连续无重号」红线的可执行化）：
 * 枚举全集逐位连续、无重号、无跳号——中途插码/删码/改号即红灯，防 ProblemDetail.errorCode
 * 漂移破坏前端提示映射与跨模块排障约定。
 */
class OutpatientErrorCodeTest {

    @Test
    @DisplayName("错误码全集 OP-1001~OP-1020 逐位连续无重号，总数二十")
    void errorCodesAreContinuousFromOp1001WithoutDuplicates() {
        List<String> codes = Arrays.stream(OutpatientErrorCode.values())
                .map(OutpatientErrorCode::getCode)
                .toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes)
                .as("错误码总数偏离 Global Constraints 全集（应为 20 条，含 SEC-02 新增 OP-1020）")
                .hasSize(20);
        for (int i = 0; i < codes.size(); i++) {
            // 逐位连续断言：第 i 个枚举码必须恰为 OP-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 OP-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("OP-%04d", 1001 + i));
        }
    }
}
