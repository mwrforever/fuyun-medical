package com.fuyun.outpatient.config;

import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.controller.ScheduleController;
import com.fuyun.outpatient.service.impl.ScheduleServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M03 门诊域 Web/服务装配集中点，Task 4 起逐任务追加 @Import 注册面——PharmacyWebConfig 同款
 * （backend 宪法 B.1 装配归 app：本类由 fuyun-app OutpatientConfig @Import 生效，禁组件扫描放宽；
 * mapper 由既有 @MapperScan 按注解自动覆盖，不入本清单）。
 */
@Configuration
@Import({PoolRedisGate.class, ScheduleServiceImpl.class, ScheduleController.class})
public class OutpatientWebConfig {}
