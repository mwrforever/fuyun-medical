package com.fuyun.nursing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 护理域错误码接续锚（Global Constraints「NS-1001 起连续无重号」红线的可执行化）：
 * 枚举全集无重号、NS-1001~NS-1016 逐位连续、NS-1017/NS-1018 预留不分配、NS-1019 收尾——
 * 中途插码/删码/改号即红灯，防 ProblemDetail.errorCode 漂移破坏前端提示映射与跨模块排障约定。
 */
class NursingErrorCodeTest {

    @Test
    @DisplayName("错误码全集 NS-1001~NS-1016 逐位连续 + NS-1019 预留位收尾，总数十七无重号")
    void errorCodesFollowFrozenSequenceWithoutDuplicates() {
        List<String> codes = Arrays.stream(NursingErrorCode.values())
                .map(NursingErrorCode::getCode)
                .toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes).as("错误码总数偏离 Global Constraints 14 全集（应为 17 条）").hasSize(17);
        for (int i = 0; i < 16; i++) {
            // 逐位连续断言：前 16 个枚举码必须恰为 NS-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 NS-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("NS-%04d", 1001 + i));
        }
        // 预留位收尾断言：NS-1017/NS-1018 不分配，第 17 个码必须恰为 NS-1019
        assertThat(codes.get(16)).as("预留位收尾码偏离（期望 NS-1019）").isEqualTo("NS-1019");
    }
}
