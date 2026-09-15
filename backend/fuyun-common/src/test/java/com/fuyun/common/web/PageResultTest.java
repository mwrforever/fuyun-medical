package com.fuyun.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分页契约单测（backend 宪法 A.3-6）：四字段取值与空清单语义。
 */
class PageResultTest {

    @Test
    @DisplayName("分页出参契约：content/page/size/total 四字段按 0 基页码原样承载")
    void carriesContractFieldsAsIs() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), 0L, 20L, 2L);

        assertThat(result.content()).containsExactly("a", "b");
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20L);
        assertThat(result.total()).isEqualTo(2L);
    }
}
