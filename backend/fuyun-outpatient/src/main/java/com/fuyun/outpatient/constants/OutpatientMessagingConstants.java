package com.fuyun.outpatient.constants;

/**
 * 门诊域消息治理常量：事件字面量与 V204 种子行、api payload record 三方一致，
 * 任何一侧变更属 CF-3/CF-5 契约变更（双向评审）。
 */
public final class OutpatientMessagingConstants {

    /** 模块域标识（信封 producer / 队列命名 / 幂等 consumerModule） */
    public static final String MODULE = "outpatient";

    /** 发布事件：申请单开立（id 23，携非药品计费行，CF-5） */
    public static final String EVENT_ORDER_CREATED = "outpatient.order.created";

    /** 发布事件：缴费放行扇出（id 25，单据精确放行清单，CF-5） */
    public static final String EVENT_ORDER_CHARGED = "outpatient.order.charged";

    /** 发布事件：退费逆向扇出（id 31，终态确认，CF-5） */
    public static final String EVENT_ORDER_CANCELLED = "outpatient.order.cancelled";

    /** 发布事件：挂号/取号成功（id 32，CF-3 visit_id 签发锚） */
    public static final String EVENT_VISIT_REGISTERED = "outpatient.visit.registered";

    /** 发布事件：诊毕（id 33） */
    public static final String EVENT_VISIT_FINISHED = "outpatient.visit.finished";

    /** 发布事件：退号回滚（id 34） */
    public static final String EVENT_VISIT_CANCELLED = "outpatient.visit.cancelled";

    /** 登记事件：门诊爽约（id 35——发布点随当日爽约判定任务，禁引用于任何发布点） */
    public static final String EVENT_REGISTRY_VISIT_NO_SHOW = "outpatient.visit.no-show";

    /** 发布事件：预约成功（id 36） */
    public static final String EVENT_APPOINTMENT_BOOKED = "outpatient.appointment.booked";

    /** 发布事件：退号完成（id 37） */
    public static final String EVENT_APPOINTMENT_CANCELLED = "outpatient.appointment.cancelled";

    /** 发布事件：改期完成（id 38） */
    public static final String EVENT_APPOINTMENT_RESCHEDULED = "outpatient.appointment.rescheduled";

    /** 发布事件：预约支付超时回调（id 39，fy.delay 档位回调，自产自消） */
    public static final String EVENT_APPOINTMENT_TIMEOUT = "outpatient.appointment.timeout";

    /** 发布事件：停诊广播（id 40） */
    public static final String EVENT_SCHEDULE_STOPPED = "outpatient.schedule.stopped";

    /** 订阅事件：PENDING 费用生成回执（id 17 既有；申请单 CREATED→PENDING_FEE 与 visit 待缴费推进） */
    public static final String EVENT_SUB_BILLING_FEE_CREATED = "billing.fee.created";

    /** 订阅事件：结算完成（id 19 既有；单据放行与 order.charged 扇出唯一权威） */
    public static final String EVENT_SUB_BILLING_SETTLEMENT_COMPLETED = "billing.settlement.completed";

    /** 订阅事件：退费审批通过（id 20 既有；退号终态与开单退费逆向，以 M13 回执为退费权威） */
    public static final String EVENT_SUB_BILLING_REFUND_APPROVED = "billing.refund.approved";

    /** 订阅事件：处方作废回流（id 26 既有；处方引用行 CANCELLED 联动，Spec :119 R2-10） */
    public static final String EVENT_SUB_PHARMACY_PRESCRIPTION_CANCELLED = "pharmacy.prescription.cancelled";

    /** 订阅事件：门诊发药完成（id 28 既有；处方引用行「已发药」聚合） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_COMPLETED = "pharmacy.dispense.completed";

    /** 订阅事件：退药受理完成（id 29 既有；引用行聚合与退费联动依据） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_RETURNED = "pharmacy.dispense.returned";

    /**
     * 订阅事件全集（队列声明与监听器同源）：随消费任务逐批追加——Task 5 补 appointment.timeout（本批），
     * Task 6 补 refund.approved、Task 8 补 fee.created、Task 10 补 settlement.completed/
     * prescription.cancelled/dispense.completed/returned；每批追加须与该任务监听器同任务落改
     * （先登记后订阅红线）。prescription.created 不订阅：M03 经 PrescriptionOpenPort 同步登记引用
     * （事件订阅为重复面，偏差注记），登记一致性由 Task 12 IT 断言 ext_ref 在位承载。
     */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {EVENT_APPOINTMENT_TIMEOUT, EVENT_SUB_BILLING_REFUND_APPROVED
    };

    /** 私有构造器（A.2-6） */
    private OutpatientMessagingConstants() {}
}
