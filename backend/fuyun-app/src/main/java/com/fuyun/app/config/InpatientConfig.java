package com.fuyun.app.config;

import org.springframework.context.annotation.Configuration;

/**
 * M04 住院模块装配：fuyun-inpatient 配置类引入 Boot 上下文的集中入口（NursingConfig 同模式，
 * 不放宽组件扫描；装配根豁免 Modulith 边界——IotConfig 引 iot/internal 先例）。
 *
 * <p>占位空壳：{@code InpatientWebConfig}（Web/服务面）与 {@code InpatientMessagingConfig}
 * （消息面——模板 Bean/发布器/订阅队列与监听器）分别由 P2 PR-1 Task 2/3 落地，届时补
 * {@code @Import({InpatientWebConfig.class, InpatientMessagingConfig.class})} 启用——先引未落地的
 * 配置类会令中间态不可编译，故 Task 1 仅保留装配锚点。
 */
@Configuration
public class InpatientConfig {}
