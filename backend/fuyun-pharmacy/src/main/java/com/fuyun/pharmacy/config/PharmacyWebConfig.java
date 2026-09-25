package com.fuyun.pharmacy.config;

import com.fuyun.pharmacy.cache.PharmacyMasterDataCache;
import com.fuyun.pharmacy.controller.DispenseController;
import com.fuyun.pharmacy.controller.DrugController;
import com.fuyun.pharmacy.controller.PrescriptionController;
import com.fuyun.pharmacy.controller.ReviewTaskController;
import com.fuyun.pharmacy.service.impl.BatchSelectServiceImpl;
import com.fuyun.pharmacy.service.impl.DispenseServiceImpl;
import com.fuyun.pharmacy.service.impl.DrugServiceImpl;
import com.fuyun.pharmacy.service.impl.MedicationReviewServiceImpl;
import com.fuyun.pharmacy.service.impl.PrescriptionCancelPortImpl;
import com.fuyun.pharmacy.service.impl.PrescriptionOpenPortImpl;
import com.fuyun.pharmacy.service.impl.PrescriptionServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M06 药事域 Web/服务装配集中点（backend 宪法 B.1 装配归 app：本类由 fuyun-app PharmacyConfig
 * @Import 生效；impl+controller 经 @Import 显式注册，禁组件扫描放宽——BillingWebConfig 同款）。
 * Task 9 追加：处方开立/作废两 api 端口实现（M03 进程内对接面，跨模块 PortImpl）。Task 10 追加
 * 注册：主数据读侧缓存（患者归一映射 + 字典版本水位，occupancy 读侧消费方）。P2 PR-1 Task 12
 * 追加注册：住院用药审方服务与工作台端点（消费 inpatient.order.created.drug 的 M06 薄切片面）。
 */
@Configuration
@Import({
    DrugServiceImpl.class,
    PrescriptionServiceImpl.class,
    BatchSelectServiceImpl.class,
    DispenseServiceImpl.class,
    PharmacyMasterDataCache.class,
    PrescriptionOpenPortImpl.class,
    PrescriptionCancelPortImpl.class,
    MedicationReviewServiceImpl.class,
    DrugController.class,
    PrescriptionController.class,
    DispenseController.class,
    ReviewTaskController.class
})
public class PharmacyWebConfig {}
