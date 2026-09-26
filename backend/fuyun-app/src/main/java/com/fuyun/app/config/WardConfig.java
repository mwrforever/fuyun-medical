package com.fuyun.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * M16 智慧病房应用模块装配占位（backend 宪法 B.1 装配归 app，与 IotConfig/InpatientConfig 同模式，
 * 不放宽组件扫描）。
 *
 * <p>P2 PR-2 Task 1 空壳落位：fuyun-ward 依赖随本批进入 fuyun-app 反应堆与 Flyway classpath
 * （V1100+ 迁移目录 spring.flyway.locations 已显式枚举），Modulith 模块图自此含 ward。
 *
 * <p>{@code @Import} 待 Task 12 ward 配置类（呼叫状态机/冷链台账/输液编排等服务与端点）落地后
 * 照 IotConfig 形态启用——本壳在 Task 12 前刻意不引用任何 ward 内部类，避免中间态不可编译；
 * ward 的 mapper 届时由既有 @MapperScan 按注解自动覆盖，无需在本壳额外登记。
 */
@Configuration
public class WardConfig {}
