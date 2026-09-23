package com.fuyun.nursing.config;

import com.fuyun.nursing.cache.NursingSeqGate;
import com.fuyun.nursing.controller.IoController;
import com.fuyun.nursing.controller.NursingAssessmentController;
import com.fuyun.nursing.controller.NursingRecordController;
import com.fuyun.nursing.controller.NursingTaskController;
import com.fuyun.nursing.controller.ShiftHandoverController;
import com.fuyun.nursing.controller.TemperatureChartController;
import com.fuyun.nursing.controller.VitalSignController;
import com.fuyun.nursing.controller.WardController;
import com.fuyun.nursing.properties.NursingProperties;
import com.fuyun.nursing.service.impl.IoRecordServiceImpl;
import com.fuyun.nursing.service.impl.NursingAssessmentServiceImpl;
import com.fuyun.nursing.service.impl.NursingOngoingVisitQuery;
import com.fuyun.nursing.service.impl.NursingRecordServiceImpl;
import com.fuyun.nursing.service.impl.NursingTaskServiceImpl;
import com.fuyun.nursing.service.impl.ShiftHandoverServiceImpl;
import com.fuyun.nursing.service.impl.TemperatureChartServiceImpl;
import com.fuyun.nursing.service.impl.VitalSignServiceImpl;
import com.fuyun.nursing.service.impl.WardMetaServiceImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M05 护理域 Web/服务装配集中点（OutpatientWebConfig 同款显式 @Import 形态——backend 宪法 B.1
 * 装配归 app：本类由 fuyun-app NursingConfig @Import 生效，禁组件扫描放宽；mapper 由既有
 * @MapperScan 按注解自动覆盖，不入本清单）。Task 3 交付：病区元数据服务、OngoingVisitQuery
 * SPI 实现（patient 合并前置检查按接口类型收集）与病区元数据控制器；订阅监听器归
 * NursingMessagingConfig（消息装配集中点）。Task 4 追加：NR 号段发号器、护理记录服务、
 * 体温单服务与两控制器。Task 5 追加：生命体征服务与体征控制器。Task 6 追加：出入量服务与
 * 出入量控制器。Task 7 追加：护理任务服务与任务控制器、护理域参数
 * （{@link NursingProperties}，BillingWebConfig @EnableConfigurationProperties 先例形态）。
 * Task 8 追加：护理评估服务（五量表引擎 + 高危联动）与评估控制器。Task 9 追加：交接班服务
 * （SBAR 自动汇总/双签/事件发布）与交接班控制器。
 */
@Configuration
@EnableConfigurationProperties(NursingProperties.class)
@Import({
    WardMetaServiceImpl.class,
    NursingOngoingVisitQuery.class,
    WardController.class,
    NursingSeqGate.class,
    NursingRecordServiceImpl.class,
    TemperatureChartServiceImpl.class,
    VitalSignServiceImpl.class,
    IoRecordServiceImpl.class,
    NursingTaskServiceImpl.class,
    NursingAssessmentServiceImpl.class,
    ShiftHandoverServiceImpl.class,
    NursingRecordController.class,
    TemperatureChartController.class,
    VitalSignController.class,
    IoController.class,
    NursingTaskController.class,
    NursingAssessmentController.class,
    ShiftHandoverController.class
})
public class NursingWebConfig {}
