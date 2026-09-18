package com.fuyun.app.config;

import com.fuyun.billing.config.BillingMessagingConfig;
import com.fuyun.billing.config.BillingWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M13 收费模块装配：fuyun-billing 配置类引入 Boot 上下文的集中入口（PatientConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 {@link BillingWebConfig}，消息面（模板 Bean/发布器/监听器/
 * 订阅队列声明）经 {@link BillingMessagingConfig} 生效。
 */
@Import({BillingWebConfig.class, BillingMessagingConfig.class})
@Configuration
public class BillingConfig {}
