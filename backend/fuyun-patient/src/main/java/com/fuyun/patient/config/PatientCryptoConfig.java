package com.fuyun.patient.config;

import com.fuyun.patient.internal.PatientFieldCrypto;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 患者域加密构件装配：构件 Bean 注册集中点（密钥 properties 已上收至 PatientWebConfig
 * 三 properties 合并声明口——2026-09-16 Task 9 去重，去重后本类不再重复 @EnableConfigurationProperties）。
 * 由 PatientWebConfig @Import 生效（com.fuyun.patient 不在组件扫描范围，backend 宪法 B.1 装配归 app）。
 */
@Configuration
@Import(PatientFieldCrypto.class)
public class PatientCryptoConfig {}
