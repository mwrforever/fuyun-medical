package com.fuyun.pharmacy.constants;

/**
 * 药事域消息治理常量：事件字面量与 V702 种子行、api payload record 三方一致，
 * 任何一侧变更属 CF-5 契约变更（双向评审）。
 */
public final class PharmacyMessagingConstants {

    /** 模块域标识（信封 producer / 队列命名 / 幂等 consumerModule） */
    public static final String MODULE = "pharmacy";

    /** 发布事件：处方生效（携药品计费行，id 24） */
    public static final String EVENT_PRESCRIPTION_CREATED = "pharmacy.prescription.created";

    /** 发布事件：处方作废（id 26） */
    public static final String EVENT_PRESCRIPTION_CANCELLED = "pharmacy.prescription.cancelled";

    /** 登记事件：审方驳回（id 27——P3 引擎接入前无发布点，禁引用于任何发布点） */
    public static final String EVENT_PRESCRIPTION_REJECTED = "pharmacy.prescription.rejected";

    /** 发布事件：门诊发药完成（id 28） */
    public static final String EVENT_DISPENSE_COMPLETED = "pharmacy.dispense.completed";

    /** 发布事件：退药受理完成（id 29） */
    public static final String EVENT_DISPENSE_RETURNED = "pharmacy.dispense.returned";

    /** 发布事件：药品字典变更广播（id 30） */
    public static final String EVENT_DRUG_CHANGED = "pharmacy.drug.changed";

    /** 订阅事件：门诊缴费放行（占位 id 25，producer=outpatient；PR-5 实装发布方） */
    public static final String EVENT_SUB_OUTPATIENT_ORDER_CHARGED = "outpatient.order.charged";

    /** 订阅事件：门诊退费逆向（占位 id 31，producer=outpatient；终态确认 PR-5 回切） */
    public static final String EVENT_SUB_OUTPATIENT_ORDER_CANCELLED = "outpatient.order.cancelled";

    /** 订阅事件：PENDING 费用生成回执（id 17 既有；驱动 APPROVED→PENDING_FEE，Spec R2-14） */
    public static final String EVENT_SUB_BILLING_FEE_CREATED = "billing.fee.created";

    /** 订阅事件：退费审批通过（id 20 既有；处方/发药单置退药终态，以 M13 回执为退费权威） */
    public static final String EVENT_SUB_BILLING_REFUND_APPROVED = "billing.refund.approved";

    /** 订阅事件：字典发布广播（V5 既有；给药途径/频次版本水位刷新，条目级消费随 P3；
     *  V607 部署期种子不发该事件——M01 管理面后续变更经广播刷新，语义顺承） */
    public static final String EVENT_SUB_SYSTEM_DICT_PUBLISHED = "system.dict.published";

    /** 订阅事件：患者合并（V105 id 11 既有；处方读侧归一最小实现，M-25 成对订阅） */
    public static final String EVENT_SUB_PATIENT_MERGED = "patient.patient.merged";

    /** 订阅事件：患者拆分（V105 id 12 既有；与 merged 成对，M-25） */
    public static final String EVENT_SUB_PATIENT_SPLIT = "patient.patient.split";

    /**
     * 订阅事件全集（队列声明与监听器同源）：Task 4 交付时为空数组（本任务仅交付发布面），
     * 随消费任务逐批追加——Task 5 补 charged/fee.created、Task 7 补 refund.approved/order.cancelled、
     * Task 10 补 dict.published/merged/split；每批追加须与该任务监听器同任务落改（先登记后订阅）。
     */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {};

    /** 私有构造器（A.2-6） */
    private PharmacyMessagingConstants() {}
}
