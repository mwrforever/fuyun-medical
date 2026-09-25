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

    /** 发布事件：挂账审批放行（V1002 id 73；M04 出院费用拦截解除唯一驱动，P2 PR-1 Task 13） */
    public static final String EVENT_ARREARS_APPROVED = "billing.arrears.approved";

    /** 订阅事件：门诊开单（CF-5 占位，PR-5 实装；V605 id 23） */
    public static final String EVENT_SUB_OUTPATIENT_ORDER_CREATED = "outpatient.order.created";

    /** 订阅事件：处方生效（CF-5 占位，PR-4 实装；V605 id 24） */
    public static final String EVENT_SUB_PHARMACY_PRESCRIPTION_CREATED = "pharmacy.prescription.created";

    /** 订阅事件：门诊发药完成（V702 id 28；执行占用标记 DISPENSED，PR-4 接线） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_COMPLETED = "pharmacy.dispense.completed";

    /** 订阅事件：退药受理完成（V702 id 29；fullReturn 占用回退 NONE，PR-4 接线） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_RETURNED = "pharmacy.dispense.returned";

    /** 订阅事件：患者入科（V800 id 48；起费锚点+当日床位费计价入口，P2 PR-1 Task 13） */
    public static final String EVENT_SUB_INPATIENT_VISIT_ADMITTED = "inpatient.visit.admitted";

    /** 订阅事件：患者转科/转床（V800 id 49；费用归属切分落行，P2 PR-1 Task 13） */
    public static final String EVENT_SUB_INPATIENT_VISIT_TRANSFERRED = "inpatient.visit.transferred";

    /** 订阅事件：出院申请（V800 id 50；停止持续性计费标记，P2 PR-1 Task 13） */
    public static final String EVENT_SUB_INPATIENT_VISIT_DISCHARGE_REQUESTED = "inpatient.visit.discharge-requested";

    /** 订阅事件：医嘱开立（V901 id 66；通配绑定离散计价数据面——items 唯一承载载荷，P2 PR-1 Task 13） */
    public static final String EVENT_SUB_INPATIENT_ORDER_CREATED = "inpatient.order.created";

    /** 订阅事件：医嘱审核通过（V800 id 41；通配到达无业务动作——冻结载荷不携 items，适配留痕见监听器） */
    public static final String EVENT_SUB_INPATIENT_ORDER_AUDITED = "inpatient.order.audited";

    /** 订阅事件：医嘱执行回签（V800 id 47；PENDING→CONFIRMED 费用确认，不发事件，P2 PR-1 Task 13） */
    public static final String EVENT_SUB_INPATIENT_ORDER_EXECUTED = "inpatient.order.executed";

    /** 订阅事件：医嘱停止（V800 id 44；在途 PENDING 费用截断作废，P2 PR-1 Task 13） */
    public static final String EVENT_SUB_INPATIENT_ORDER_STOPPED = "inpatient.order.stopped";

    /**
     * 订阅绑定键：住院就诊域事件通配（生产方 inpatient）。governance 订阅登记按登记名精确匹配
     * 且命名审查拒绝 # 通配段（QueueGovernorImpl EVENT_TYPE_PATTERN/registerSubscriber），无法
     * 承载通配绑定——故本队列经 BillingMessagingConfig 自声明（Task 12 drug 子键队列自声明同款
     * 先例；exchange/队列前缀字面量与治理约定同源，非私建交换机）。
     */
    public static final String BINDING_KEY_INPATIENT_VISIT_ALL = "inpatient.visit.#";

    /** 订阅绑定键：住院医嘱域事件通配（含 audited.executed 等类型子键帧，同上自声明先例） */
    public static final String BINDING_KEY_INPATIENT_ORDER_ALL = "inpatient.order.#";

    /** 主交换机（治理三件套字面量，与 integration 治理实现同源；仅声明队列绑定不禁用） */
    public static final String TOPIC_EXCHANGE = "fy.topic";

    /** 死信交换机（治理约定字面量；消费队列死信统一指向） */
    public static final String DLX_EXCHANGE = "fy.dlx";

    /** 消费队列命名前缀（治理约定 q.&lt;consumerModule&gt;.&lt;eventType&gt; 字面量） */
    public static final String CONSUMER_QUEUE_PREFIX = "q.";

    /**
     * 订阅事件全集（治理声明队列与监听器同源）：住院六事件经通配绑定自声明队列消费
     * （BINDING_KEY_INPATIENT_*_ALL，命名审查与精确登记无法承载通配段），不入本治理声明清单——
     * Task 12 drug 子键队列同款口径（先登记后订阅红线：六事件登记面归 V800/V901 既有行）。
     */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {
        EVENT_SUB_OUTPATIENT_ORDER_CREATED,
        EVENT_SUB_PHARMACY_PRESCRIPTION_CREATED,
        EVENT_SUB_PHARMACY_DISPENSE_COMPLETED,
        EVENT_SUB_PHARMACY_DISPENSE_RETURNED
    };

    /** 私有构造器（A.2-6） */
    private BillingMessagingConstants() {}
}
