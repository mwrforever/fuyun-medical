package com.fuyun.inpatient.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 住院域错误码接续锚（Global Constraints「IP-1001 起连续无重号」红线的可执行化）：
 * 枚举全集无重号、IP-1001~IP-1023 逐位连续收尾——中途插码/删码/改号即红灯，
 * 防 ProblemDetail.errorCode 漂移破坏前端提示映射与跨模块排障约定。
 */
class InpatientErrorCodeTest {

    @Test
    @DisplayName("错误码全集 IP-1001~IP-1023 逐位连续，总数二十三无重号")
    void errorCodesFollowFrozenSequenceWithoutDuplicates() {
        List<String> codes = Arrays.stream(InpatientErrorCode.values())
                .map(InpatientErrorCode::getCode)
                .toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes).as("错误码总数偏离 Global Constraints 14 全集（应为 23 条）").hasSize(23);
        for (int i = 0; i < 23; i++) {
            // 逐位连续断言：全部枚举码必须恰为 IP-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 IP-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("IP-%04d", 1001 + i));
        }
    }
}
