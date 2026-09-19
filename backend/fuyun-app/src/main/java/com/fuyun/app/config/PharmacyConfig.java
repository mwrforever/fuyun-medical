package com.fuyun.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * M06 药事模块装配：fuyun-pharmacy 配置类引入 Boot 上下文的集中入口（BillingConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 PharmacyWebConfig、消息面经 PharmacyMessagingConfig——
 * 两配置类随 Task 4/5 落码后在 {@code @Import} 逐任务追加（PR-3 装配分段接线同款；
 * 本任务先落空壳使 app 依赖可编译、Modulith 把 pharmacy 入图）。
 */
@Configuration
public class PharmacyConfig {}
