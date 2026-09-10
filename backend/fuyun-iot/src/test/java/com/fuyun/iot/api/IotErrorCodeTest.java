package com.fuyun.iot.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M14 模块错误码枚举契约测试（backend 宪法 A.3-4：格式 {@code <模块助记>-<4位数字>}，全项目唯一）。
 */
class IotErrorCodeTest {

    @Test
    @DisplayName("全部错误码格式为 IOT-4位数字且枚举内无重复")
    void allCodesFollowModuleFormatAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (IotErrorCode errorCode : IotErrorCode.values()) {
            // IOT- 前缀 + 4 位数字（模块助记 IOT 归 M14 独占，当前全项目无其他占用）
            assertThat(errorCode.getCode())
                    .as("错误码 %s 应符合 IOT-xxxx 格式", errorCode)
                    .matches("IOT-\\d{4}");
            // 枚举内唯一性（全项目唯一由评审与号段台账保障，此处守住模块内不撞号）
            assertThat(seen.add(errorCode.getCode()))
                    .as("错误码 %s 在枚举内应唯一", errorCode.getCode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("兜底通道鉴权失败码实现 common ErrorCode 契约且声明值与 getCode 一致")
    void fallbackAuthCodeImplementsCommonContract() {
        // 对外契约：BizException 携带本枚举，经全局渲染输出 ProblemDetail.properties.errorCode（B4.3 兜底端点使用）
        assertThat(IotErrorCode.FALLBACK_AUTH_FAILED.getCode()).isEqualTo("IOT-1001");
    }
}
