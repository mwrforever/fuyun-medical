package com.fuyun.inpatient.constants;

/**
 * M04 住院域消息治理常量：20 个发布事件字面量（V800 既有 id 41–52 十二条 + V901 新增 id 65–72
 * 八条）与 inpatient/api/payload 载荷 record 组件名三方一致（契约锚 InpatientMessagingContractTest），
 * 任何一侧变更属 CF-6 契约变更（双向评审）。另含五条消费事件字面量（先登记后订阅红线：
 * pharmacy 段 V800 id 53/54 回执两条 + billing 段 V605 id 19/21 两条与 V1002 id 73 欠费审批放行一条）。
 */
public final class InpatientMessagingConstants {

    /** 模块域标识（信封 producer / 队列命名 / 幂等 consumerModule） */
    public static final String MODULE = "inpatient";

    /** 消费队列命名前缀（q.&lt;module&gt;.&lt;eventType&gt;，与治理构件 declareConsumerQueue 同源推导） */
    public static final String QUEUE_PREFIX = "q." + MODULE + ".";

    /** 发布事件：医嘱审核通过（V800 id 41；routing key 携带类型子键 drug 等，R3-06） */
    public static final String EVENT_ORDER_AUDITED = "inpatient.order.audited";

    /** 发布事件：医嘱转抄（V800 id 42；M05 据此生成临时医嘱单次执行单） */
    public static final String EVENT_ORDER_TRANSFERRED = "inpatient.order.transferred";

    /** 发布事件：长期医嘱计划拆分（V800 id 43；M05 据此批量生成次日执行单） */
    public static final String EVENT_ORDER_PLAN_GENERATED = "inpatient.order-plan.generated";

    /** 发布事件：医嘱停止（V800 id 44；M05 撤销未执行执行单） */
    public static final String EVENT_ORDER_STOPPED = "inpatient.order.stopped";

    /** 发布事件：医嘱作废（V800 id 45；M05 撤销未执行执行单并拦截在途核对） */
    public static final String EVENT_ORDER_CANCELLED = "inpatient.order.cancelled";

    /** 发布事件：医嘱撤回·转抄前（V800 id 46；无执行单无订阅方，登记保证契约完整） */
    public static final String EVENT_ORDER_REVOKED = "inpatient.order.revoked";

    /** 发布事件：医嘱执行回签聚合完成（V800 id 47；M13 据此确认住院费用） */
    public static final String EVENT_ORDER_EXECUTED = "inpatient.order.executed";

    /** 发布事件：患者入科（V800 id 48；M05 维护病区患者本地视图） */
    public static final String EVENT_VISIT_ADMITTED = "inpatient.visit.admitted";

    /** 发布事件：患者转科/转床（V800 id 49；M05 病区视图变更与执行单重定向） */
    public static final String EVENT_VISIT_TRANSFERRED = "inpatient.visit.transferred";

    /** 发布事件：出院申请（V800 id 50；M05 清退在途任务提示） */
    public static final String EVENT_VISIT_DISCHARGE_REQUESTED = "inpatient.visit.discharge-requested";

    /** 发布事件：患者出院终态（V800 id 51；M05 终清在途任务与执行单） */
    public static final String EVENT_VISIT_DISCHARGED = "inpatient.visit.discharged";

    /** 发布事件：床位动态变更（V800 id 52；M05 护士站一览与大屏床位动态） */
    public static final String EVENT_BED_CHANGED = "inpatient.bed.changed";

    /** 发布事件：入院登记（V901 id 65；M13 医保入院办理登记依据；载荷 VisitRegisteredPayload） */
    public static final String EVENT_VISIT_REGISTERED = "inpatient.visit.registered";

    /** 发布事件：医嘱开立（V901 id 66；routing key 携带类型子键，drug 子键驱动 M06 审方任务生成；载荷 OrderCreatedPayload） */
    public static final String EVENT_ORDER_CREATED = "inpatient.order.created";

    /** 发布事件：医嘱审核驳回（V901 id 67；M06 回执驱动，医生站修改重提路径；载荷 OrderAuditRejectedPayload） */
    public static final String EVENT_ORDER_AUDIT_REJECTED = "inpatient.order.audit-rejected";

    /** 发布事件：会诊申请（V901 id 68；五态共用 ConsultationPayload，急会诊 30min 时限读时惰性判定） */
    public static final String EVENT_CONSULTATION_REQUESTED = "inpatient.consultation.requested";

    /** 发布事件：会诊响应（V901 id 69；受邀科接受） */
    public static final String EVENT_CONSULTATION_ACCEPTED = "inpatient.consultation.accepted";

    /** 发布事件：会诊完成（V901 id 70） */
    public static final String EVENT_CONSULTATION_COMPLETED = "inpatient.consultation.completed";

    /** 发布事件：会诊超时升级动作（V901 id 71；动作广播非状态迁移，状态停留 REQUESTED 仍可响应） */
    public static final String EVENT_CONSULTATION_OVERDUE = "inpatient.consultation.overdue";

    /** 发布事件：会诊取消（V901 id 72） */
    public static final String EVENT_CONSULTATION_CANCELLED = "inpatient.consultation.cancelled";

    /** 消费事件：住院医嘱审方通过（V800 id 53；M06 回执——医嘱置可执行） */
    public static final String EVENT_SUB_PHARMACY_MEDICATION_ORDER_AUDIT_COMPLETED =
            "pharmacy.medication-order.audit-completed";

    /** 消费事件：住院医嘱审方驳回（V800 id 54；M06 回执——医嘱置驳回态） */
    public static final String EVENT_SUB_PHARMACY_MEDICATION_ORDER_AUDIT_REJECTED =
            "pharmacy.medication-order.audit-rejected";

    /** 消费事件：押金变动（V605 id 21；欠费标识/押金余额联动） */
    public static final String EVENT_SUB_BILLING_DEPOSIT_CHANGED = "billing.deposit.changed";

    /** 消费事件：结算完成（V605 id 19；出院结算放行联动） */
    public static final String EVENT_SUB_BILLING_SETTLEMENT_COMPLETED = "billing.settlement.completed";

    /** 消费事件：欠费挂账审批放行（V1002 id 73；挂账审批通过解除费用拦截） */
    public static final String EVENT_SUB_BILLING_ARREARS_APPROVED = "billing.arrears.approved";

    /** 订阅事件全集（队列声明唯一来源；先登记后订阅红线，消费任务逐批追加） */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {
        EVENT_SUB_PHARMACY_MEDICATION_ORDER_AUDIT_COMPLETED,
        EVENT_SUB_PHARMACY_MEDICATION_ORDER_AUDIT_REJECTED,
        EVENT_SUB_BILLING_DEPOSIT_CHANGED,
        EVENT_SUB_BILLING_SETTLEMENT_COMPLETED,
        EVENT_SUB_BILLING_ARREARS_APPROVED
    };

    /**
     * 拼接携带类型子键的 routing key（登记名不带子键，R3-06）。
     *
     * <p>orderType 取值（M04 医嘱九类，小写）：drug（药品→M06 审方）/lab（检验）/exam（检查）/
     * surgery（手术）/blood（用血）/nursing（护理——不经 order.audited 子键分发，经转抄链进 M05）/
     * diet（膳食）/consult（会诊）/discharge-med（出院带药）。
     *
     * @param eventType 事件登记名（如 inpatient.order.audited），非空；来源：本类发布事件常量
     * @param orderType 医嘱类型子键（小写），非空；来源：医嘱 orderType 字典值小写形态
     * @return routing key（eventType + "." + orderType，如 inpatient.order.created.drug）
     */
    public static String withTypeKey(String eventType, String orderType) {
        return eventType + "." + orderType;
    }

    /** 私有构造器（A.2-6） */
    private InpatientMessagingConstants() {}
}
