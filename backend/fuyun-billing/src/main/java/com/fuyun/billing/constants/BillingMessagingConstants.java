package com.fuyun.billing.constants;

/**
 * 收费域消息治理常量：事件字面量与 V605 种子行、api payload record 三方一致，
 * 任何一侧变更属 CF-4/CF-5 契约变更（双向评审）。
 */
public final class BillingMessagingConstants {

    /** 模块域标识（信封 producer / 队列命名 / 幂等 consumerModule） */
    public static final String MODULE = "billing";

    /** 发布事件：费用生成 */
    public static final String EVENT_FEE_CREATED = "billing.fee.created";

    /** 发布事件：费用确认入账 */
    public static final String EVENT_FEE_CONFIRMED = "billing.fee.confirmed";

    /** 发布事件：结算完成 */
    public static final String EVENT_SETTLEMENT_COMPLETED = "billing.settlement.completed";

    /** 发布事件：退费审批通过 */
    public static final String EVENT_REFUND_APPROVED = "billing.refund.approved";

    /** 发布事件：押金账户变更 */
    public static final String EVENT_DEPOSIT_CHANGED = "billing.deposit.changed";

    /** 发布事件：调价生效广播 */
    public static final String EVENT_PRICE_PUBLISHED = "billing.charge-item-price.published";

    /** 订阅事件：门诊开单（CF-5 占位，PR-5 实装；V605 id 23） */
    public static final String EVENT_SUB_OUTPATIENT_ORDER_CREATED = "outpatient.order.created";

    /** 订阅事件：处方生效（CF-5 占位，PR-4 实装；V605 id 24） */
    public static final String EVENT_SUB_PHARMACY_PRESCRIPTION_CREATED = "pharmacy.prescription.created";

    /** 订阅事件：门诊发药完成（V702 id 28；执行占用标记 DISPENSED，PR-4 接线） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_COMPLETED = "pharmacy.dispense.completed";

    /** 订阅事件：退药受理完成（V702 id 29；fullReturn 占用回退 NONE，PR-4 接线） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_RETURNED = "pharmacy.dispense.returned";

    /** 订阅事件全集（队列声明与监听器同源） */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {
        EVENT_SUB_OUTPATIENT_ORDER_CREATED,
        EVENT_SUB_PHARMACY_PRESCRIPTION_CREATED,
        EVENT_SUB_PHARMACY_DISPENSE_COMPLETED,
        EVENT_SUB_PHARMACY_DISPENSE_RETURNED
    };

    /** 私有构造器（A.2-6） */
    private BillingMessagingConstants() {}
}
