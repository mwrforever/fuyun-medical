package com.fuyun.patient.config;

import com.fuyun.patient.internal.PatientFieldCrypto;
import com.fuyun.patient.properties.PatientCryptoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 患者域加密构件装配：密钥 properties 启动期校验（@Validated 构造器绑定）与构件 Bean 注册集中点。
 * 由 PatientWebConfig @Import 生效（com.fuyun.patient 不在组件扫描范围，backend 宪法 B.1 装配归 app）。
 */
@Configuration
@EnableConfigurationProperties(PatientCryptoProperties.class)
@Import(PatientFieldCrypto.class)
public class PatientCryptoConfig {}
