package com.fuyun.app.config;

import com.fuyun.patient.config.PatientMessagingConfig;
import com.fuyun.patient.config.PatientWebConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M02 患者主索引模块装配：fuyun-patient 配置类引入 Boot 上下文的集中入口（SystemConfig 同模式，
 * 不放宽组件扫描）；Web/服务面经 {@link PatientWebConfig}，消息面（发布器/自消费队列/扫描任务）经
 * {@link PatientMessagingConfig} 生效。
 */
@Import({PatientWebConfig.class, PatientMessagingConfig.class})
@Configuration
public class PatientConfig {}
