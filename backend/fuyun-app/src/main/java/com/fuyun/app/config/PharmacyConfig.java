package com.fuyun.app.config;

import com.fuyun.pharmacy.config.PharmacyMessagingConfig;
import com.fuyun.pharmacy.config.PharmacyWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M06 药事模块装配：fuyun-pharmacy 配置类引入 Boot 上下文的集中入口（BillingConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 {@link PharmacyWebConfig}，消息面（模板 Bean/发布器/
 * 订阅队列声明与监听器）经 {@link PharmacyMessagingConfig} 生效。
 */
@Import({PharmacyWebConfig.class, PharmacyMessagingConfig.class})
@Configuration
public class PharmacyConfig {}
