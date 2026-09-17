package com.fuyun.common.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 角色上下文单测：设置读取、null 落空清单、清理后回退空清单三态（永不为 null 契约）。 */
class RoleContextHolderTest {

    @AfterEach
    void tearDown() {
        RoleContextHolder.clear();
    }

    @Test
    @DisplayName("set 后 get 返回同清单；null 落空清单；clear 后回退空清单")
    void setGetClearLifecycleNeverNull() {
        RoleContextHolder.set(List.of("ADMIN", "DOCTOR"));
        assertThat(RoleContextHolder.get()).containsExactly("ADMIN", "DOCTOR");
        RoleContextHolder.set(null);
        assertThat(RoleContextHolder.get()).isEmpty();
        RoleContextHolder.clear();
        assertThat(RoleContextHolder.get()).isEmpty();
    }
}
