package com.fuyun.iot.service;

import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.entity.IotAlarmEntity;
import com.fuyun.iot.entity.IotTelemetryEntity;
import com.fuyun.iot.vo.DashboardSummaryVO;
import java.time.Instant;
import java.util.List;

/**
 * 遥测 STOMP 推送服务：/ws/iot 主题推送的唯一封装执行点（BRIEF-PR4-01 §4，P0 直推语义）。
 *
 * <p>推送目标为内存 SimpleBroker（/topic 前缀）——进程内分发至已订阅 WebSocket 会话，非 MQ
 * 代理发送。调用时点约束（宪法 A.4.2-7"事务内禁止远程调用、消息发送与人工等待，对外调用在
 * 事务提交后执行"）：调用方须保证处于<b>事务提交后</b>或无事务上下文（入库侧以 afterCommit
 * 回调承接，监听器侧运行于 MQ 消费线程）；多实例经 MQ 扇出推送网关的完整化 P1
 * （14-iot FU-M14-07）。两个主题（简报 §1.4/§1.5）：
 *
 * <ul>
 *   <li>/topic/iot/telemetry/{wardId}：遥测批量落库成功后的摘要帧（P2 PR-2 Task 11 起经 2 秒窗口
 *       聚合器按病区合并节流推送，同窗口多批合并单帧）；</li>
 *   <li>/topic/iot/device-status/{wardId}：设备状态事件经 fy.topic 往返（发布→自消费）后的
 *       状态帧（调用方 IotFanoutListener；载荷 wardId 为空时跳过推送）；</li>
 *   <li>/topic/iot/alarm/{wardId}：告警帧（P2 PR-2 Task 7 告警引擎分级通知面，载荷 =
 *       AlarmTriggeredPayload 契约 record；INFO/WARNING/CRITICAL 全推，风暴抑制④的非危急
 *       延迟推送由调用方裁决）；</li>
 *   <li>/topic/iot/dashboard/global：全院运营摘要帧（P2 PR-2 Task 11 四主题完整化，载荷 =
 *       DashboardSummaryVO；summary 变更触发推送，调用方 DashboardServiceImpl——实测无 @Scheduled
 *       心跳先例，真栈心跳形态留 Task 18 验证注记）。</li>
 * </ul>
 *
 * <p>落 api/service 契约包：消费者（internal）与入库服务（service.impl）两类调用方共用同一语言；
 * 订阅级数据范围校验与 2 秒窗口节流属 P1（§0 负面清单，P0 每批一帧）。
 */
public interface ITelemetryPushService {

    /**
     * 摘要帧 items 明细上限（防 1009 大消息——载荷轻量化口径，14-iot §9）：超限批次 items 截断，
     * 条数字段保留真实值，订阅方以 count 为准。
     */
    int SUMMARY_MAX_ITEMS = 100;

    /**
     * 遥测批量落库成功后的摘要帧推送（每批一帧，频率 = 落库频率，非逐帧）。
     *
     * <p>执行流程：wardId 为 null（或批次为空）info 跳过；否则构建轻量摘要 record（条数 /
     * occurredAt 上界 / items 明细，items 截至上限防大消息）convertAndSend 至
     * /topic/iot/telemetry/{wardId}。推送失败向调用方原样抛出——入库服务侧按辅助语义吞并告警
     * （落库是主职责），消费监听器侧按业务失败处置。
     *
     * @param written 本批已写入（含唯一键冲突忽略行）的遥测实体，非空；来源：TelemetryIngestServiceImpl
     *                落库成功后按病区分组透传
     * @param wardId  病区 ID（本批绑定快照），允许为 null（无绑定快照：仅落库不推送，info 留痕）；
     *                来源：iot_binding.ward_id 写入时快照
     */
    void pushSummary(List<IotTelemetryEntity> written, Long wardId);

    /**
     * 设备状态变更事件的状态帧推送（自事件消费后执行，载荷与事件契约同构四字段）。
     *
     * @param event 设备状态变更事件，非空；来源：IotFanoutListener 自事件消费解析产物（载荷
     *              wardId 由 AMQP 消费者以设备档案补全，可空——为空时 info 跳过推送，属防御
     *              口径：未编病区设备在发布侧已省略事件）
     */
    void pushDeviceStatus(DeviceStatusEvent event);

    /**
     * 告警帧推送（FU-M14-08 分级通知面，P2 PR-2 Task 7）：INFO/WARNING/CRITICAL 三级全部推送
     * 至 /topic/iot/alarm/{wardId}（载荷 = triggered 事件契约 record，订阅方同构消费）；风暴抑制④
     * 的「非危急不推」由调用方（AlarmEngine）裁决后仍经本方法补推。
     *
     * <p>PDA/手环/短信承载缺位（通知中心后续阶段补齐，注记归 Spec 任务）——本方法仅承载 WS 面。
     *
     * @param alarm 已落库告警实体，非空；来源：AlarmEngine 触发链（新发/风暴解除补推）
     */
    void pushAlarm(IotAlarmEntity alarm);

    /**
     * 联动 NOTIFY 动作的 WS 告警主题重复强化推送（FU-M14-10，P2 PR-2 Task 9）：与告警帧同题
     * 重推一次至 /topic/iot/alarm/{wardId}——「重复强化」语义即同载荷帧再推一次（订阅方同构
     * 消费，AlarmTriggeredPayload 契约不变），linkageNo 经 STOMP 消息头（linkageNo 头）携带作
     * 联动标记，不改 V1004 冻结载荷契约。
     *
     * <p>调用时点约束同 {@link #pushAlarm}（宪法 A.4.2-7：事务提交后或无事务上下文）；推送失败
     * 原样抛出，由联动执行器按失败重试语义承接。
     *
     * @param alarm     已落库告警实体，非空；来源：联动执行器按 trigger_ref（告警号）定位
     * @param linkageNo 联动执行业务号，非空；来源：IotSeqGate.nextLinkageNo（人工重推沿既有号）
     */
    void pushLinkageNotify(IotAlarmEntity alarm, String linkageNo);

    /**
     * 全院运营摘要帧推送（FU-M14-13 四主题完整化，P2 PR-2 Task 11）：载荷 = DashboardSummaryVO
     * （REST 快照兜底同构契约），主题 /topic/iot/dashboard/global（全院主题不分病区）。推送时点
     * 为 summary 变更触发（DashboardServiceImpl.refreshAndPushIfChanged 变更判定后调用）——
     * 实测推送面无 @Scheduled 心跳先例（既有 @Scheduled 均为 ShedLock 互斥业务 Job，照抄做心跳
     * 将单实例发帧且绕开宪法 A.5-14），真栈心跳形态留 Task 18 验证注记。
     *
     * @param summary 全院摘要视图，非空；来源：DashboardServiceImpl 聚合产物（变更检测通过）
     */
    void pushDashboardSummary(DashboardSummaryVO summary);

    /**
     * 遥测摘要帧载荷（轻量 record，防大消息）。
     *
     * @param count                本批实体条数（真实批大小，不随 items 截断减少）
     * @param occurredAtUpperBound 本批最大发生时刻（UTC 语义，摘要时间锚点）
     * @param items                明细列表（deviceId+metricCode 二元组，截至 SUMMARY_MAX_ITEMS 条）
     */
    record TelemetrySummary(int count, Instant occurredAtUpperBound, List<Item> items) {}

    /**
     * 摘要明细二元组（订阅方识别本批涉及的设备与指标）。
     *
     * @param deviceId   IoTDA 设备标识，非空
     * @param metricCode 指标编码，非空
     */
    record Item(String deviceId, String metricCode) {}
}
