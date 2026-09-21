package com.fuyun.outpatient.config;

import com.fuyun.outpatient.cache.PoolRedisGate;
import com.fuyun.outpatient.cache.QueueZsetStore;
import com.fuyun.outpatient.controller.AppointmentController;
import com.fuyun.outpatient.controller.ApptCreditController;
import com.fuyun.outpatient.controller.OrderController;
import com.fuyun.outpatient.controller.PortalAppointmentController;
import com.fuyun.outpatient.controller.QueueController;
import com.fuyun.outpatient.controller.ScheduleController;
import com.fuyun.outpatient.controller.TriageController;
import com.fuyun.outpatient.controller.VisitController;
import com.fuyun.outpatient.service.impl.AppointmentServiceImpl;
import com.fuyun.outpatient.service.impl.ChargingServiceImpl;
import com.fuyun.outpatient.service.impl.ClinicOrderServiceImpl;
import com.fuyun.outpatient.service.impl.OutpatientCareRelationQuery;
import com.fuyun.outpatient.service.impl.OutpatientOngoingVisitQuery;
import com.fuyun.outpatient.service.impl.ScheduleServiceImpl;
import com.fuyun.outpatient.service.impl.TriageServiceImpl;
import com.fuyun.outpatient.service.impl.VisitIdIssuerImpl;
import com.fuyun.outpatient.service.impl.VisitServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M03 门诊域 Web/服务装配集中点，Task 4 起逐任务追加 @Import 注册面——PharmacyWebConfig 同款
 * （backend 宪法 B.1 装配归 app：本类由 fuyun-app OutpatientConfig @Import 生效，禁组件扫描放宽；
 * mapper 由既有 @MapperScan 按注解自动覆盖，不入本清单）。Task 5 追加：visit_id 签发器、预约服务、
 * OngoingVisitQuery SPI 实现（patient 合并前置检查按接口类型收集）与预约/portal/信用三控制器。
 * Task 7 追加：队列 ZSET 存储、分诊台服务与分诊/队列两控制器（WS 面经 fuyun-app OutpatientConfig
 * 直挂 OutpatientWebSocketConfig，不入本清单）。Task 8 追加：医生站 visit/开单两服务、CareRelationQuery
 * SPI 实现（patient unmask 第二道门禁 D-16 收紧）与医生站/申请单两控制器（fee.created 监听器归
 * OutpatientMessagingConfig）。
 */
@Configuration
@Import({
    PoolRedisGate.class,
    QueueZsetStore.class,
    ScheduleServiceImpl.class,
    ScheduleController.class,
    VisitIdIssuerImpl.class,
    AppointmentServiceImpl.class,
    OutpatientOngoingVisitQuery.class,
    TriageServiceImpl.class,
    AppointmentController.class,
    PortalAppointmentController.class,
    ApptCreditController.class,
    TriageController.class,
    QueueController.class,
    VisitServiceImpl.class,
    ClinicOrderServiceImpl.class,
    ChargingServiceImpl.class,
    OutpatientCareRelationQuery.class,
    VisitController.class,
    OrderController.class
})
public class OutpatientWebConfig {}
