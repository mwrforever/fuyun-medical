package com.fuyun.nursing.vo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 护士站大屏 WS 推送统一信封（FU-M05-08，Task 11——brief 冻结 {@code {type, payload, occurredAt}}
 * 纯 JSON 形态，前端纯函数收窄的唯一入参面）：经 /topic/nursing/board/{wardId} 分发的全部帧
 * 一律以本信封承载，type 词表五值冻结（派发上下文 §3），payload 按各类型嵌套载荷承载。
 *
 * <p><b>type 词表（冻结，禁自增值）</b>：
 * <ul>
 * <li>{@link #TYPE_BED_PATIENT}——床位患者动态（Task 7 投影 listener 四路提交后推送）；
 * <li>{@link #TYPE_TASK_OVERDUE}——任务逾期（Task 9 tick 段①首标/段②升级档逐行推送）；
 * <li>{@link #TYPE_INFUSION_ESCALATION}——输注升级强提醒（Task 6 告警 listener 升级动作推送）；
 * <li>{@link #TYPE_ADVERSE_EVENT_REMIND}——不良事件 I/II 级上报时限超时提醒（Task 10 tick
 * 搭载扫描段按病区聚合推送，不改状态非惩罚口径）；
 * <li>{@link #TYPE_CALL_TRIGGERED}——设备呼叫转发（iot.call.triggered 第 14 条消费队列转推；
 * <b>M16 呼叫状态事件未发布——转发为触发通知非全状态同步，降级注记</b>；topic 尾段为 iot 域
 * 病区 id 数字串，与护理病区编码分属两个标识空间，见 IotCallTriggeredListener javadoc）。
 * </ul>
 *
 * <p>occurredAt 为事件发生时刻（Instant，UTC 绝对时点——与 iot 帧同构序列化形态）；推送执行
 * 时序归 {@code NurseBoardPushListener}（AFTER_COMMIT + fallback，事务内禁推送红线）。
 *
 * @param type       帧类型（本类 TYPE_* 词表），非空
 * @param payload    类型化载荷（本类嵌套 record 按类型取用），非空
 * @param occurredAt 事件发生时刻，非空
 */
public record NurseBoardPushFrame(String type, Object payload, Instant occurredAt) {

    /** 床位患者动态（投影行变更：入科/转科/出院/床位变更） */
    public static final String TYPE_BED_PATIENT = "BED_PATIENT";

    /** 任务逾期（首标与升级档统一承载，escalationCount 区分档位） */
    public static final String TYPE_TASK_OVERDUE = "TASK_OVERDUE";

    /** 输注升级强提醒（告警挂单升级动作——不新建任务纪律） */
    public static final String TYPE_INFUSION_ESCALATION = "INFUSION_ESCALATION";

    /** 不良事件 I/II 级上报时限超时提醒（非惩罚只提醒） */
    public static final String TYPE_ADVERSE_EVENT_REMIND = "ADVERSE_EVENT_REMIND";

    /** 设备呼叫转发（触发通知非全状态同步——M16 降级注记） */
    public static final String TYPE_CALL_TRIGGERED = "CALL_TRIGGERED";

    /**
     * 床位患者动态载荷（TYPE_BED_PATIENT）：投影行变更定位键——前端据此定向刷新床位墙行。
     *
     * @param visitId   住院就诊号，非空
     * @param patientId 患者主索引，非空
     * @param bedNo     床位号文本（变更后态；入科空占位时为空串），可空
     * @param wardId    变更归属病区编码（路由 topic 同源），非空
     */
    public record BedPatientPayload(String visitId, Long patientId, String bedNo, String wardId) {}

    /**
     * 任务逾期载荷（TYPE_TASK_OVERDUE，taskNo/wardId 维度——派发上下文 §1.2 口径）。
     *
     * @param taskNo          任务业务号，非空
     * @param taskType        任务类型 code，非空
     * @param planTime        计划时间，非空
     * @param escalationCount 递增后档位（1=责任护士档、2=护士长档封顶）
     * @param wardId          任务归属病区编码（路由 topic 同源），非空
     */
    public record OverdueTaskPayload(
            String taskNo, String taskType, OffsetDateTime planTime, int escalationCount, String wardId) {}

    /**
     * 输注升级载荷（TYPE_INFUSION_ESCALATION——原 Task 6 brief 载荷语义：alarmNo/患者维/
     * 升级行数/任务上调数）。
     *
     * @param alarmNo           告警业务号（挂单锚），非空
     * @param executionNos      患者维执行单号清单（本次升级命中行），非空
     * @param escalatedCount    升级命中行数
     * @param taskEscalatedCount 挂接任务优先级上调行数（不新建任务——0=无挂接任务）
     */
    public record InfusionEscalationPayload(
            String alarmNo, List<String> executionNos, int escalatedCount, int taskEscalatedCount) {}

    /**
     * 不良事件超时提醒载荷（TYPE_ADVERSE_EVENT_REMIND——按病区聚合，样例事件号有界 5 条防刷屏）。
     *
     * @param wardId         超时行归属病区编码（路由 topic 同源），非空
     * @param overdueCount   该病区 I/II 级超时行数
     * @param sampleEventNos 样例事件号（至多 5 条），非空
     */
    public record AdverseEventRemindPayload(String wardId, int overdueCount, List<String> sampleEventNos) {}

    /**
     * 设备呼叫转发载荷（TYPE_CALL_TRIGGERED——iot.call.triggered 冻结子集镜像，消费侧禁依赖
     * iot api 包经信封 JSON 直读的反向成环红线；M16 联调冻结后可切换直存语义）。
     *
     * @param callNo      呼叫业务号（iot 侧触发引用），非空
     * @param deviceId    触发设备标识，非空
     * @param callType    呼叫类型 code，非空
     * @param bedId       床位 id（iot 域数字标识，可缺位——IoTDA 直发路径床号可缺）
     * @param wardId      病区 id（iot 域数字标识——<b>非护理病区编码</b>，路由 topic 尾段直用
     *                    其数字串，标识空间注记见类注释），非空
     * @param triggeredAt 触发时刻，非空
     */
    public record CallTriggeredPayload(
            String callNo, String deviceId, String callType, Long bedId, Long wardId, Instant triggeredAt) {}
}
