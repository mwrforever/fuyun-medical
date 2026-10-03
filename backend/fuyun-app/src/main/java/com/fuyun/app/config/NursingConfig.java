package com.fuyun.app.config;

import com.fuyun.nursing.config.NursingWebConfig;
import com.fuyun.nursing.config.NursingWebSocketConfig;
import com.fuyun.nursing.internal.NursingMessagingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M05 护理模块装配：fuyun-nursing 配置类引入 Boot 上下文的集中入口（OutpatientConfig 同模式，
 * 不放宽组件扫描；装配根豁免 Modulith 边界——IotConfig 引 iot/internal 先例）。
 * 消息面 {@code NursingMessagingConfig}（模板 Bean/发布器/订阅队列与监听器）已于 Task 2 起经
 * {@code @Import} 启用；Web/服务面 {@code NursingWebConfig}（Task 3 病区元数据域）经 {@code @Import} 生效。
 * P2 PR-3 Task 11 追加：WS 面 {@code NursingWebSocketConfig}（/ws/nursing 端点 + SimpleBroker
 * /topic + CONNECT 帧鉴权拦截器 + 大屏推送监听器——与 iot/outpatient 双 configurer 共存，
 * 端点叠加/前缀同值幂等，装配语义见其 javadoc）。
 */
@Configuration
@Import({NursingMessagingConfig.class, NursingWebConfig.class, NursingWebSocketConfig.class})
public class NursingConfig {}
