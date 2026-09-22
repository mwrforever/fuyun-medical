package com.fuyun.nursing.config;

import com.fuyun.nursing.controller.WardController;
import com.fuyun.nursing.service.impl.NursingOngoingVisitQuery;
import com.fuyun.nursing.service.impl.WardMetaServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M05 护理域 Web/服务装配集中点（OutpatientWebConfig 同款显式 @Import 形态——backend 宪法 B.1
 * 装配归 app：本类由 fuyun-app NursingConfig @Import 生效，禁组件扫描放宽；mapper 由既有
 * @MapperScan 按注解自动覆盖，不入本清单）。Task 3 交付：病区元数据服务、OngoingVisitQuery
 * SPI 实现（patient 合并前置检查按接口类型收集）与病区元数据控制器；订阅监听器归
 * NursingMessagingConfig（消息装配集中点）。
 */
@Configuration
@Import({WardMetaServiceImpl.class, NursingOngoingVisitQuery.class, WardController.class})
public class NursingWebConfig {}
