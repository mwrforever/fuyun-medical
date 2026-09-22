package com.fuyun.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * M05 护理模块装配占位：fuyun-nursing 配置类引入 Boot 上下文的集中入口（OutpatientConfig 同模式，
 * 不放宽组件扫描）。PR-6 当前为空壳占位——Task 2/3 落地 {@code NursingMessagingConfig}（消息面：
 * 模板 Bean/发布器/订阅队列声明）与 {@code NursingWebConfig}（Web/服务面）后，在此启用
 * {@code @Import({NursingWebConfig.class, NursingMessagingConfig.class})} 完成装配接线；
 * 空壳期 nursing 模块经 Modulith 应用模块图自动入图（ModulithBoundaryTest 的
 * ApplicationModules.verify() 守护循环依赖与包边界）。
 */
@Configuration
public class NursingConfig {}
