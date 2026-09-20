package com.fuyun.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * M03 门诊模块装配：fuyun-outpatient 配置类引入 Boot 上下文的集中入口（PharmacyConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 OutpatientWebConfig、消息面经 OutpatientMessagingConfig、
 * WS 面经 OutpatientWebSocketConfig——三配置类随 Task 3/5/7 落码后在 {@code @Import} 逐任务
 * 追加（PR-4 装配分段接线同款；本任务先落空壳使 app 依赖可编译、Modulith 把 outpatient 入图）。
 */
@Configuration
public class OutpatientConfig {}
