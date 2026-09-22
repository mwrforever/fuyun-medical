package com.fuyun.app.config;

import com.fuyun.nursing.internal.NursingMessagingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M05 护理模块装配：fuyun-nursing 配置类引入 Boot 上下文的集中入口（OutpatientConfig 同模式，
 * 不放宽组件扫描；装配根豁免 Modulith 边界——IotConfig 引 iot/internal 先例）。
 * 消息面 {@code NursingMessagingConfig}（模板 Bean/发布器）已于 Task 2 经 {@code @Import} 启用；
 * Web/服务面 {@code NursingWebConfig} 归 Task 3 落地后补入 @Import（避免空壳重复）。
 */
@Configuration
@Import(NursingMessagingConfig.class)
public class NursingConfig {}
