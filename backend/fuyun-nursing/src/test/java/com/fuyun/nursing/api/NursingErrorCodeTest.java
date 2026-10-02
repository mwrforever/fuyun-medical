package com.fuyun.nursing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 护理域错误码接续锚（Global Constraints「NS-1001 起连续无重号」红线的可执行化）：
 * 枚举全集无重号、NS-1001~NS-1016 逐位连续、NS-1017/NS-1018 预留不分配、NS-1019 收尾、
 * P2 PR-3 执行域续号 NS-1020~1024 与 NS-1027 在位——中途插码/删码/改号即红灯，防
 * ProblemDetail.errorCode 漂移破坏前端提示映射与跨模块排障约定。NS-1025/1026 排定随
 * Task 10 追加（CHANGELOG 2026-10-01 立项③），到位后本断言同步扩位。
 */
class NursingErrorCodeTest {

    @Test
    @DisplayName("错误码全集无重号且按冻结序在位（P1 十七 + 执行域六 = 二十三）")
    void errorCodesFollowFrozenSequenceWithoutDuplicates() {
        List<String> codes = Arrays.stream(NursingErrorCode.values())
                .map(NursingErrorCode::getCode)
                .toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes).as("错误码总数偏离冻结全集（应为 23 条）").hasSize(23);
        for (int i = 0; i < 16; i++) {
            // 逐位连续断言：前 16 个枚举码必须恰为 NS-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 NS-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("NS-%04d", 1001 + i));
        }
        // 预留位收尾断言：NS-1017/NS-1018 不分配，第 17 个码必须恰为 NS-1019
        assertThat(codes.get(16)).as("预留位收尾码偏离（期望 NS-1019）").isEqualTo("NS-1019");
        // P2 PR-3 执行域续号段：NS-1020~1024 连续接续（NS-1024 输液面随 Task 6 到位扩位；
        // NS-1025/1026 不良事件面随 Task 10 追加后继续扩位）
        for (int i = 0; i < 5; i++) {
            assertThat(codes.get(17 + i))
                    .as("执行域续号第 %d 位偏离（期望 NS-%04d）", i + 1, 1020 + i)
                    .isEqualTo(String.format("NS-%04d", 1020 + i));
        }
        // 尾位断言：时间窗码 NS-1027 收尾（NS-1025/1026 到位前跳位冻结留痕）
        assertThat(codes.get(22)).as("执行域尾码偏离（期望 NS-1027）").isEqualTo("NS-1027");
    }
}
