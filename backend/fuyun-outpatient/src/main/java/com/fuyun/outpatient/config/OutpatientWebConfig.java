package com.fuyun.outpatient.config;

import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.controller.AppointmentController;
import com.fuyun.outpatient.controller.PortalAppointmentController;
import com.fuyun.outpatient.controller.ScheduleController;
import com.fuyun.outpatient.service.impl.AppointmentServiceImpl;
import com.fuyun.outpatient.service.impl.OutpatientOngoingVisitQuery;
import com.fuyun.outpatient.service.impl.ScheduleServiceImpl;
import com.fuyun.outpatient.service.impl.VisitIdIssuerImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M03 门诊域 Web/服务装配集中点，Task 4 起逐任务追加 @Import 注册面——PharmacyWebConfig 同款
 * （backend 宪法 B.1 装配归 app：本类由 fuyun-app OutpatientConfig @Import 生效，禁组件扫描放宽；
 * mapper 由既有 @MapperScan 按注解自动覆盖，不入本清单）。Task 5 追加：visit_id 签发器、预约服务、
 * OngoingVisitQuery SPI 实现（patient 合并前置检查按接口类型收集）与预约/portal 两控制器。
 */
@Configuration
@Import({
    PoolRedisGate.class,
    ScheduleServiceImpl.class,
    ScheduleController.class,
    VisitIdIssuerImpl.class,
    AppointmentServiceImpl.class,
    OutpatientOngoingVisitQuery.class,
    AppointmentController.class,
    PortalAppointmentController.class
})
public class OutpatientWebConfig {}
