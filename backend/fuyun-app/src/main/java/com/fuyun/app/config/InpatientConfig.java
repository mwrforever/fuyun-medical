package com.fuyun.app.config;

import com.fuyun.inpatient.config.InpatientWebConfig;
import com.fuyun.inpatient.internal.InpatientMessagingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M04 住院模块装配：fuyun-inpatient 配置类引入 Boot 上下文的集中入口（NursingConfig 同模式，
 * 不放宽组件扫描；装配根豁免 Modulith 边界——IotConfig 引 iot/internal 先例）。
 *
 * <p>Task 3 起启用双面 @Import：{@code InpatientWebConfig}（Web/服务面——发号器/入院登记域
 * 服务/OngoingVisitQuery SPI/六端点控制器）与 {@code InpatientMessagingConfig}（消息面——
 * 发送模板 Bean/发布器/五条订阅队列治理声明）。
 */
@Configuration
@Import({InpatientWebConfig.class, InpatientMessagingConfig.class})
public class InpatientConfig {}
