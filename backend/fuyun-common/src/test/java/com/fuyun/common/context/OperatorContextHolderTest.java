package com.fuyun.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 操作人上下文单元测试：验证 set/get 一致性与 clear 后无残留（防线程复用泄漏）。
 */
class OperatorContextHolderTest {

    @AfterEach
    void cleanUp() {
        // 兜底清理，避免影响同线程后续测试
        OperatorContextHolder.clear();
    }

    @Test
    @DisplayName("set 后 get 返回同一操作人标识")
    void returnsSameOperatorAfterSet() {
        OperatorContextHolder.set("EMP-1001");

        assertThat(OperatorContextHolder.get()).isEqualTo("EMP-1001");
    }

    @Test
    @DisplayName("clear 后 get 返回 null，验证无残留防止线程复用泄漏")
    void returnsNullAfterClear() {
        OperatorContextHolder.set("EMP-1001");

        OperatorContextHolder.clear();

        assertThat(OperatorContextHolder.get()).isNull();
    }
}
