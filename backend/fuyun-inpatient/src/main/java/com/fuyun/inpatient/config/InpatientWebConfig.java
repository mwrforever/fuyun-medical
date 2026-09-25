package com.fuyun.inpatient.config;

import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.controller.AdmissionController;
import com.fuyun.inpatient.service.impl.AdmissionServiceImpl;
import com.fuyun.inpatient.service.impl.InpatientOngoingVisitQuery;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M04 住院域 Web/服务装配集中点（NursingWebConfig 同款显式 @Import 形态——backend 宪法 B.1
 * 装配归 app：本类由 fuyun-app InpatientConfig @Import 生效，禁组件扫描放宽；mapper 由既有
 * @MapperScan 按注解自动覆盖，不入本清单）。Task 3 交付：住院号段发号器、入院登记域服务、
 * OngoingVisitQuery SPI 实现（patient 合并前置检查按接口类型收集，与 nursing 实现并存——
 * 任一命中即阻断）与入院登记六端点控制器。订阅监听器与发送模板归 InpatientMessagingConfig
 * （消息装配集中点）。Task 4 追加：床位管理服务与 BedService 联动（schedule/cancel/admit-ward
 * 的床位预占/释放/占床面）。Task 5+ 追加：医嘱开立/审核/转抄等域服务与控制器。
 */
@Configuration
@Import({InpatientSeqGate.class, AdmissionServiceImpl.class, InpatientOngoingVisitQuery.class, AdmissionController.class
})
public class InpatientWebConfig {}
