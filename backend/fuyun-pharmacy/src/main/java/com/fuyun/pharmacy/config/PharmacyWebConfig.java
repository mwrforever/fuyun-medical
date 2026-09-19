package com.fuyun.pharmacy.config;

import com.fuyun.pharmacy.controller.DrugController;
import com.fuyun.pharmacy.controller.PrescriptionController;
import com.fuyun.pharmacy.service.impl.BatchSelectServiceImpl;
import com.fuyun.pharmacy.service.impl.DispenseServiceImpl;
import com.fuyun.pharmacy.service.impl.DrugServiceImpl;
import com.fuyun.pharmacy.service.impl.PrescriptionServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M06 药事域 Web/服务装配集中点（backend 宪法 B.1 装配归 app：本类由 fuyun-app PharmacyConfig
 * @Import 生效；impl+controller 经 @Import 显式注册，禁组件扫描放宽——BillingWebConfig 同款）。
 * 后续任务追加注册：Task 10 占用查询（@Import 清单逐批扩展）。
 */
@Configuration
@Import({
    DrugServiceImpl.class,
    PrescriptionServiceImpl.class,
    BatchSelectServiceImpl.class,
    DispenseServiceImpl.class,
    DrugController.class,
    PrescriptionController.class
})
public class PharmacyWebConfig {}
