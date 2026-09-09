package com.fuyun.system.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M01 模块错误码枚举契约测试（backend 宪法 A.3-4：格式 {@code <模块助记>-<4位数字>}，全项目唯一）。
 */
class SystemErrorCodeTest {

    @Test
    @DisplayName("全部错误码格式为 SYS-4位数字且枚举内无重复")
    void allCodesFollowModuleFormatAndAreUnique() {
        Set<String> seen = new HashSet<>();
        for (SystemErrorCode errorCode : SystemErrorCode.values()) {
            // SYS- 前缀 + 4 位数字（模块助记 SYS 归 M01 独占，当前全项目无其他占用）
            assertThat(errorCode.getCode())
                    .as("错误码 %s 应符合 SYS-xxxx 格式", errorCode)
                    .matches("SYS-\\d{4}");
            // 枚举内唯一性（全项目唯一由评审与号段台账保障，此处守住模块内不撞号）
            assertThat(seen.add(errorCode.getCode()))
                    .as("错误码 %s 在枚举内应唯一", errorCode.getCode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("错误码实现 common ErrorCode 契约且声明值与 getCode 一致")
    void implementsCommonErrorCodeContract() {
        // 对外契约：BizException 携带本枚举，经全局渲染输出 ProblemDetail.properties.errorCode
        assertThat(SystemErrorCode.LOGIN_NAME_OR_PASSWORD_WRONG.getCode()).isEqualTo("SYS-1001");
        assertThat(SystemErrorCode.TOKEN_MISSING_OR_INVALID.getCode()).isEqualTo("SYS-1003");
        assertThat(SystemErrorCode.TOKEN_EXPIRED.getCode()).isEqualTo("SYS-1004");
    }
}
