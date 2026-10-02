package com.fuyun.nursing.constants;

/**
 * 护理域消息治理常量：十事件字面量与 V800/V1109 种子行（id 56–64、83）、nursing/api 载荷 record
 * 组件名三方一致（GC4 红线，契约锚 NursingEventContractTest），任何一侧变更属 CF-6 契约变更（双向评审）。
 * 另含消费事件字面量（先登记后订阅红线）：M02 段 V105 id 11/12/16（Task 3 三订阅，成对口径 M-25）、
 * CF-6 冻结载体段（id 41–55；P2 PR-3 起消费 inpatient 九条，其余按彼时契约追加，禁虚构登记外字面量）、
 * P2 PR-3 执行域十三订阅（inpatient 九 + pharmacy 摆药签收一 + iot 告警三，Task 4–7 消费面）。
 * tick 豁免口径：ROUTING_TASK_OVERDUE_TICK 为延迟档位到期转发路由键而非事件——不入 event_registry、
 * 不入 SUBSCRIBED_EVENT_TYPES（declareDelayQueue 无先登记校验，A.5-7 延迟档位语义）。
 */
public final class NursingMessagingConstants {

    /** 模块域标识（信封 producer / 队列命名 / 幂等 consumerModule） */
    public static final String MODULE = "nursing";

    /** 消费队列命名前缀（q.&lt;module&gt;.&lt;eventType&gt;，与治理构件 declareConsumerQueue 同源推导） */
    public static final String QUEUE_PREFIX = "q." + MODULE + ".";

    /** 发布事件：体征记录转正入卡（id 56，Task 5 发布；载荷 VitalSignRecordedPayload） */
    public static final String EVENT_VITAL_SIGN_RECORDED = "nursing.vital-sign.recorded";

    /** 发布事件：护理评估完成（id 57，Task 8 发布；载荷 AssessmentCompletedPayload） */
    public static final String EVENT_ASSESSMENT_COMPLETED = "nursing.assessment.completed";

    /** 发布事件：护理任务生成（id 58，Task 7 发布；载荷 TaskCreatedPayload） */
    public static final String EVENT_TASK_CREATED = "nursing.task.created";

    /** 发布事件：护理任务完成/取消（id 59，Task 7 发布；载荷 TaskCompletedPayload） */
    public static final String EVENT_TASK_COMPLETED = "nursing.task.completed";

    /** 发布事件：交接班完成（id 60，Task 9 发布；载荷 ShiftCompletedPayload） */
    public static final String EVENT_SHIFT_COMPLETED = "nursing.shift.completed";

    /** 发布事件：护理任务逾期升级广播（id 61，P1 占位登记——读时惰性判定不发布，P2 由延迟队列驱动实装） */
    public static final String EVENT_TASK_OVERDUE = "nursing.task.overdue";

    /** 发布事件：开始输注（id 62，P1 占位登记——FU-M05-06 归 P2 实装） */
    public static final String EVENT_INFUSION_STARTED = "nursing.infusion.started";

    /** 发布事件：拔针/输注结束（id 63，P1 占位登记——FU-M05-06 归 P2 实装） */
    public static final String EVENT_INFUSION_COMPLETED = "nursing.infusion.completed";

    /** 发布事件：执行单执行回执（id 64，P1 占位登记——execute-confirm 双路对账辅路径，FU-M05-04 归 P2 实装） */
    public static final String EVENT_ORDER_EXECUTION_COMPLETED = "nursing.order-execution.completed";

    /** 发布事件：护理不良事件上报（id 83，V1109 首登——匿名通道不含 reporter；Task 10 发布；载荷 AdverseEventReportedPayload） */
    public static final String EVENT_ADVERSE_EVENT_REPORTED = "nursing.adverse-event.reported";

    /** 延迟档位到期转发路由键（delay.task-overdue 到期经 DLX 回投 fy.topic 的目标键；非事件不入 event_registry，豁免注记见类注释） */
    public static final String ROUTING_TASK_OVERDUE_TICK = "nursing.task-overdue.tick";

    /** 任务逾期延迟档位队列名（delay.task-overdue；Task 9 自续期投递目标，与 NursingMessagingConfig 档位声明同源） */
    public static final String DELAY_QUEUE_TASK_OVERDUE = "delay.task-overdue";

    /** 逾期 tick 消费队列名（Task 9 tick 监听器绑定 fy.topic 路由键 nursing.task-overdue.tick 的消费队列） */
    public static final String QUEUE_TASK_OVERDUE_TICK = "q.nursing.task-overdue.tick";

    /** 消费事件：健康档案变更/过敏摘要刷新（V105 id 16，Task 3 订阅；载荷 PatientHealthSummaryUpdatedPayload） */
    public static final String EVENT_SUB_PATIENT_HEALTH_SUMMARY_UPDATED = "patient.health-summary.updated";

    /** 消费事件：患者合并完成（V105 id 11，Task 3 订阅；载荷 PatientMergedPayload；成对订阅读侧 M-25） */
    public static final String EVENT_SUB_PATIENT_PATIENT_MERGED = "patient.patient.merged";

    /** 消费事件：患者拆分恢复（V105 id 12，Task 3 成对订阅；载荷 PatientSplitPayload，merged 的逆事件） */
    public static final String EVENT_SUB_PATIENT_PATIENT_SPLIT = "patient.patient.split";

    /** 消费事件：医嘱转抄（V800 id 42，Task 4 订阅——临时医嘱单次执行单生成） */
    public static final String EVENT_SUB_INPATIENT_ORDER_TRANSFERRED = "inpatient.order.transferred";

    /** 消费事件：长期医嘱计划拆分（V800 id 43，Task 4 订阅——次日执行单批量生成） */
    public static final String EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED = "inpatient.order-plan.generated";

    /** 消费事件：医嘱停止（V800 id 44，Task 4 订阅——撤销未执行执行单） */
    public static final String EVENT_SUB_INPATIENT_ORDER_STOPPED = "inpatient.order.stopped";

    /** 消费事件：医嘱作废（V800 id 45，Task 4 订阅——撤销未执行执行单并拦截在途核对） */
    public static final String EVENT_SUB_INPATIENT_ORDER_CANCELLED = "inpatient.order.cancelled";

    /** 消费事件：患者入科（V800 id 48，Task 7 订阅——病区患者投影 admitted upsert 写入面） */
    public static final String EVENT_SUB_INPATIENT_VISIT_ADMITTED = "inpatient.visit.admitted";

    /** 消费事件：患者转科/转床（V800 id 49，Task 7 订阅——投影更新归属与未执行执行单重定向） */
    public static final String EVENT_SUB_INPATIENT_VISIT_TRANSFERRED = "inpatient.visit.transferred";

    /** 消费事件：出院申请（V800 id 50，Task 4 订阅——清退在途任务提示） */
    public static final String EVENT_SUB_INPATIENT_VISIT_DISCHARGE_REQUESTED = "inpatient.visit.discharge-requested";

    /** 消费事件：患者出院终态（V800 id 51，Task 4/7 订阅——终清在途任务执行单与投影逻辑删） */
    public static final String EVENT_SUB_INPATIENT_VISIT_DISCHARGED = "inpatient.visit.discharged";

    /** 消费事件：床位动态变更（V800 id 52，Task 7 订阅——投影床号更新，护士站一览/大屏联动） */
    public static final String EVENT_SUB_INPATIENT_BED_CHANGED = "inpatient.bed.changed";

    /** 消费事件：住院摆药签收完成（V702 id 28，Task 6 订阅——输液挂接摆药袋签关联；P2 PR-3 Task 3 升级 desc） */
    public static final String EVENT_SUB_PHARMACY_DISPENSE_COMPLETED = "pharmacy.dispense.completed";

    /** 消费事件：IoT 告警触发（V1004 id 74，Task 6 订阅——输液速率告急挂接升级） */
    public static final String EVENT_SUB_IOT_ALARM_TRIGGERED = "iot.alarm.triggered";

    /** 消费事件：IoT 告警升级（V1004 id 75，Task 6 订阅——升级挂单/任务联动） */
    public static final String EVENT_SUB_IOT_ALARM_ESCALATED = "iot.alarm.escalated";

    /** 消费事件：IoT 告警关闭（V1004 id 76，Task 6 订阅——监测链路收口） */
    public static final String EVENT_SUB_IOT_ALARM_CLOSED = "iot.alarm.closed";

    /** 订阅事件全集（队列声明唯一来源；先登记后订阅红线，消费任务逐批追加；P2 PR-3 扩至十六项） */
    public static final String[] SUBSCRIBED_EVENT_TYPES = {
        EVENT_SUB_PATIENT_HEALTH_SUMMARY_UPDATED,
        EVENT_SUB_PATIENT_PATIENT_MERGED,
        EVENT_SUB_PATIENT_PATIENT_SPLIT,
        EVENT_SUB_INPATIENT_ORDER_TRANSFERRED,
        EVENT_SUB_INPATIENT_ORDER_PLAN_GENERATED,
        EVENT_SUB_INPATIENT_ORDER_STOPPED,
        EVENT_SUB_INPATIENT_ORDER_CANCELLED,
        EVENT_SUB_INPATIENT_VISIT_ADMITTED,
        EVENT_SUB_INPATIENT_VISIT_TRANSFERRED,
        EVENT_SUB_INPATIENT_VISIT_DISCHARGE_REQUESTED,
        EVENT_SUB_INPATIENT_VISIT_DISCHARGED,
        EVENT_SUB_INPATIENT_BED_CHANGED,
        EVENT_SUB_PHARMACY_DISPENSE_COMPLETED,
        EVENT_SUB_IOT_ALARM_TRIGGERED,
        EVENT_SUB_IOT_ALARM_ESCALATED,
        EVENT_SUB_IOT_ALARM_CLOSED
    };

    /** 私有构造器（A.2-6） */
    private NursingMessagingConstants() {}
}
