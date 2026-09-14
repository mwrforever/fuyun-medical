package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Modulith 模块边界守护（进 verify 门禁，宪法 B.2-6/D-2 裁决）：模块间 internal 访问、依赖环、
 * 未声明 allowedDependencies 违规即构建失败。
 *
 * <p>排除谓词豁免 com.fuyun.app..：装配根（宪法 B.1）@Import 各模块 impl/config 属聚合职责，
 * 非业务模块间依赖；模块间（system/iot/integration/common 等）规则全量生效。
 * 与 ArchUnit 既有规则并存分工：Modulith 管模块级边界，ArchUnit 管自定义分层规则（D-2 裁决）。
 */
class ModulithBoundaryTest {

    @Test
    @DisplayName("模块边界校验：internal 跨模块访问与依赖环零容忍")
    void verifyModuleBoundaries() {
        ApplicationModules modules =
                ApplicationModules.of(FuyunApplication.class, JavaClass.Predicates.resideInAPackage("com.fuyun.app.."));
        // 控制台输出模块布局（本地/CI 日志排查用，含各模块暴露接口与 Spring Bean 清单）
        System.out.println(modules);
        // failOnEmptyShould=false 的补偿防线：模型为空（排除谓词被误放宽）时显式失败，
        // 杜绝边界守护静默失效（守护面现 4 模块，随装配依赖扩大自动增长）
        assertThat(modules.stream().count()).as("模块模型不得为空：排除谓词误放宽将使边界校验静默绿过").isGreaterThanOrEqualTo(4);
        // verify() 在 1.4.x 返回 ApplicationModules 供链式调用，违规时直接抛 Violations 异常使测试失败
        modules.verify();
    }
}
