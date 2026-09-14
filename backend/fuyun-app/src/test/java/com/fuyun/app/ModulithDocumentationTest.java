package com.fuyun.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.File;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Modulith 模块依赖图生成（D-2 裁决：CI 产出 PlantUML 图作为架构评审依据）。
 * Documenter 默认写出 target/spring-modulith-docs/：总图 components.puml + 各模块 module-*.puml
 * （1.4.13 实测默认名：总图自 1.4 起由历史文档的 modules.puml 更名为 components.puml）。
 */
class ModulithDocumentationTest {

    @Test
    @DisplayName("生成模块依赖 PlantUML 图并落盘")
    void writeModuleDocumentation() {
        // 与 ModulithBoundaryTest 同款构造：排除谓词豁免装配根 com.fuyun.app..，只对业务模块出图
        ApplicationModules modules =
                ApplicationModules.of(FuyunApplication.class, JavaClass.Predicates.resideInAPackage("com.fuyun.app.."));

        // 总图 + 各模块 individual 图两步链式写出（1.4.13 Documenter 实际 API，javap 已核验）
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();

        // 断言总图落盘（surefire 工作目录为模块 basedir，相对路径即 backend/fuyun-app/target/...；
        // 文件名为 1.4.13 字节码常量实测值 components.puml）
        assertThat(new File("target/spring-modulith-docs/components.puml"))
                .as("模块总图应落盘（Documenter 默认输出目录）")
                .exists();
    }
}
