package com.fuyun.nursing.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 护理域错误码接续锚（Global Constraints「NS-1001 起连续无重号」红线的可执行化）：
 * 枚举全集无重号、NS-1001~NS-1016 逐位连续、NS-1017/NS-1018 预留不分配、NS-1019 收尾、
 * P2 PR-3 执行域续号 NS-1020~1027 全集在位（NS-1025/1026 不良事件面已随 Task 10 扩位，
 * 立项排定全集至此齐备；P2 PR-4C Task 4 续号 NS-1028 病区归属校验面随本 PR 扩位；
 * P2 PR-4E Task 6 续号 NS-1029/1030 频控面随本 PR 扩位——65ed593 同款接续锚扩位口径）——
 * 中途插码/删码/改号即红灯，防 ProblemDetail.errorCode 漂移
 * 破坏前端提示映射与跨模块排障约定。
 */
class NursingErrorCodeTest {

    @Test
    @DisplayName("错误码全集无重号且按冻结序在位（P1 十七 + 执行域八 + 病区防线一 + 频控二 = 二十八）")
    void errorCodesFollowFrozenSequenceWithoutDuplicates() {
        List<String> codes = Arrays.stream(NursingErrorCode.values())
                .map(NursingErrorCode::getCode)
                .toList();
        assertThat(codes).as("错误码存在重号").doesNotHaveDuplicates();
        assertThat(codes).as("错误码总数偏离冻结全集（应为 28 条）").hasSize(28);
        for (int i = 0; i < 16; i++) {
            // 逐位连续断言：前 16 个枚举码必须恰为 NS-(1001+i)，插码/跳号/改号任一漂移即红灯
            assertThat(codes.get(i))
                    .as("第 %d 个错误码偏离接续序列（期望 NS-%04d）", i + 1, 1001 + i)
                    .isEqualTo(String.format("NS-%04d", 1001 + i));
        }
        // 预留位收尾断言：NS-1017/NS-1018 不分配，第 17 个码必须恰为 NS-1019
        assertThat(codes.get(16)).as("预留位收尾码偏离（期望 NS-1019）").isEqualTo("NS-1019");
        // P2 PR-3 执行域续号段：NS-1020~1026 连续接续（NS-1025/1026 不良事件面随 Task 10 扩位到位）
        for (int i = 0; i < 7; i++) {
            assertThat(codes.get(17 + i))
                    .as("执行域续号第 %d 位偏离（期望 NS-%04d）", i + 1, 1020 + i)
                    .isEqualTo(String.format("NS-%04d", 1020 + i));
        }
        // 尾位断言：时间窗码 NS-1027 之后由病区防线码 NS-1028 收尾（P2 PR-4C Task 4 扩位），
        // 频控双码 NS-1029/1030 顺延收尾（P2 PR-4E Task 6 扩位——上报限频+PDA 枚举冷却）
        assertThat(codes.get(24)).as("执行域尾码偏离（期望 NS-1027）").isEqualTo("NS-1027");
        assertThat(codes.get(25)).as("病区防线尾码偏离（期望 NS-1028）").isEqualTo("NS-1028");
        assertThat(codes.get(26)).as("上报频控码偏离（期望 NS-1029）").isEqualTo("NS-1029");
        assertThat(codes.get(27)).as("PDA 冷却码偏离（期望 NS-1030）").isEqualTo("NS-1030");
    }
}
