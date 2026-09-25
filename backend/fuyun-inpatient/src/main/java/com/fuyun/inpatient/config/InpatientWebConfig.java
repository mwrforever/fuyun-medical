package com.fuyun.inpatient.config;

import com.fuyun.inpatient.cache.InpatientSeqGate;
import com.fuyun.inpatient.controller.AdmissionController;
import com.fuyun.inpatient.controller.BedController;
import com.fuyun.inpatient.controller.DischargeController;
import com.fuyun.inpatient.controller.OrderController;
import com.fuyun.inpatient.controller.OrderPlanController;
import com.fuyun.inpatient.controller.TransferController;
import com.fuyun.inpatient.controller.VisitTransferController;
import com.fuyun.inpatient.properties.InpatientProperties;
import com.fuyun.inpatient.service.impl.AdmissionServiceImpl;
import com.fuyun.inpatient.service.impl.BedServiceImpl;
import com.fuyun.inpatient.service.impl.DischargeServiceImpl;
import com.fuyun.inpatient.service.impl.InpatientOngoingVisitQuery;
import com.fuyun.inpatient.service.impl.MedicalOrderServiceImpl;
import com.fuyun.inpatient.service.impl.OrderAuditServiceImpl;
import com.fuyun.inpatient.service.impl.OrderPlanServiceImpl;
import com.fuyun.inpatient.service.impl.OrderStateMachineServiceImpl;
import com.fuyun.inpatient.service.impl.OrderTransferServiceImpl;
import com.fuyun.inpatient.service.impl.TransferServiceImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * TransferServiceImpl 预注入装配链自此闭合）及开立三端点控制器。Task 6 追加：医嘱审核与
 * 控制域（V905 两表业务面）——审核与控制服务（系统自动审核/药师回执驱动/作废撤回重整/
 * 口头确认；MedicalOrderServiceImpl 审核链收口自此闭合）与状态机留痕回接
 * （appendStatusLog 落库）。Task 7 追加：转抄与执行计划域（V906 两表业务面）——转抄与
 * 执行计划服务（工作台/批量转抄核对/计划查询/嘱托触发/转科三分钩子；TransferServiceImpl
 * 阶段②钩子自此闭合，MedicalOrderServiceImpl 停嘱联动未来计划作废回接落库）与转抄/计划
 * 三端点控制器。订阅监听器与发送模板归 InpatientMessagingConfig（消息装配集中点）。
 * Task 8 追加：执行计划域（日切分解/当日补偿/CF-6 执行回签/闭环追溯——OrderPlanServiceImpl
 * 注入 OrderTransferServiceImpl 补偿衔接面自此闭合）。
 * Task 9 追加：出院管理域（V907 两表业务面）——出院管理服务（在途清理编排/费用预审/双条件
 * 离院确认/带药放行/随访生成；BillingAccountQueryPort 实现归 billing 侧 Task 13 落地后
 * fuyun-app 上下文闭合）与出院四端点控制器。
 * Task 10 追加：住院域参数（{@link InpatientProperties}——押金下限阈值/随访缺省时距/默认
 * 准备窗口，NursingWebConfig @EnableConfigurationProperties 先例形态，env 注入见
 * application.yml fuyun.inpatient 段）——AdmissionServiceImpl 欠费面、DischargeServiceImpl
 * 随访缺省与 OrderTransferServiceImpl 准备窗口三处回接取值。
 */
@Configuration
@EnableConfigurationProperties(InpatientProperties.class)
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
    OrderAuditServiceImpl.class,
    OrderTransferServiceImpl.class,
    OrderPlanServiceImpl.class,
    DischargeServiceImpl.class,
    OrderController.class,
    TransferController.class,
    OrderPlanController.class,
    DischargeController.class
})
public class InpatientWebConfig {}
