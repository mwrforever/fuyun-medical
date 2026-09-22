package com.fuyun.nursing.constants;

/**
 * 护理域消息治理常量：九事件字面量与 V800 种子行（id 56–64）、nursing/api 载荷 record 组件名三方一致
 * （GC4 红线，契约锚 NursingEventContractTest），任何一侧变更属 CF-6 契约变更（双向评审）。
 * 另含 CF-6 冻结载体段的消费字面量（id 41–55，M04/M06 P2 实装时按彼时契约追加订阅常量，禁在本类虚构）。
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

    /** 私有构造器（A.2-6） */
    private NursingMessagingConstants() {}
}
