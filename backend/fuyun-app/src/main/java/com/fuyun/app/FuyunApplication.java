package com.fuyun.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * 后端统一可执行入口（装配模块）：只承担 Spring Boot 启动装配，不放任何业务逻辑（backend 宪法 B.1）。
 *
 * <p>业务能力全部由 fuyun-{domain} 业务模块与 fuyun-common 公共模块装配提供；
 * 本地启动需数据库/Redis/RabbitMQ 等基础设施在位（编排见 deploy/）。
 *
 * <p>@Modulithic：Modulith 模块检测根声明（D-2 裁决）——默认根为本类所在包（com.fuyun.app，
 * 会把 config/properties 误判为模块），additionalPackages 将 com.fuyun 声明为额外检测根，
 * 使 21 个业务/公共模块（com.fuyun.* 直接子包）进入模块模型；com.fuyun.app 装配根本身
 * 在边界测试中以排除谓词豁免（见 ModulithBoundaryTest）。
 */
@Modulithic(systemName = "fuyun-medical", additionalPackages = "com.fuyun")
@SpringBootApplication
public class FuyunApplication {

    /**
     * 应用入口。
     *
     * @param args 命令行启动参数，可为空；外部传入时覆盖配置文件（如 --spring.profiles.active=dev）
     */
    public static void main(String[] args) {
        SpringApplication.run(FuyunApplication.class, args);
    }
}
