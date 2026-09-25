package com.fuyun.inpatient.config;

import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.controller.AdmissionController;
import com.fuyun.inpatient.controller.BedController;
import com.fuyun.inpatient.controller.OrderController;
import com.fuyun.inpatient.controller.VisitTransferController;
import com.fuyun.inpatient.service.impl.AdmissionServiceImpl;
import com.fuyun.inpatient.service.impl.BedServiceImpl;
import com.fuyun.inpatient.service.impl.InpatientOngoingVisitQuery;
import com.fuyun.inpatient.service.impl.MedicalOrderServiceImpl;
import com.fuyun.inpatient.service.impl.OrderStateMachineServiceImpl;
import com.fuyun.inpatient.service.impl.TransferServiceImpl;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * M04 住院域 Web/服务装配集中点（NursingWebConfig 同款显式 @Import 形态——backend 宪法 B.1
 * 装配归 app：本类由 fuyun-app InpatientConfig @Import 生效，禁组件扫描放宽；mapper 由既有
 * @MapperScan 按注解自动覆盖，不入本清单）。Task 3 交付：住院号段发号器、入院登记域服务、
 * OngoingVisitQuery SPI 实现（patient 合并前置检查按接口类型收集，与 nursing 实现并存——
 * 任一命中即阻断）与入院登记六端点控制器。Task 4 追加：床位管理服务（五态状态机与占用
 * 流水权威）、转科/转床编排服务（构造注入 MedicalOrderService——实现归 Task 5，其
 * MedicalOrderServiceImpl 落地前装配链待闭合）、床位七端点与转科转床两端点控制器，及
 * AdmissionServiceImpl 的床位联动注入。Task 5 追加：医嘱开立域（V904 三表业务面）——医嘱
 * 状态机服务（八态合法迁移表唯一裁决面）与医嘱开立服务（四层校验/开立/查询/停嘱，
 * TransferServiceImpl 预注入装配链自此闭合）及开立三端点控制器。订阅监听器与发送模板归
 * InpatientMessagingConfig（消息装配集中点）。Task 6+ 追加：审核/转抄等域服务与控制器。
 */
@Configuration
@Import({
    InpatientSeqGate.class,
    AdmissionServiceImpl.class,
    InpatientOngoingVisitQuery.class,
    AdmissionController.class,
    BedServiceImpl.class,
    TransferServiceImpl.class,
    BedController.class,
    VisitTransferController.class,
    OrderStateMachineServiceImpl.class,
    MedicalOrderServiceImpl.class,
    OrderController.class
})
public class InpatientWebConfig {}
