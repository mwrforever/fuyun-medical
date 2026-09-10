package com.fuyun.iot.service;

import com.fuyun.iot.api.DeviceStatusEvent;
import com.fuyun.iot.entity.IotTelemetryEntity;
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
 *   <li>/topic/iot/telemetry/{wardId}：遥测批量落库成功后的摘要帧（wardId 取绑定快照，无绑定
 *       快照的帧仅落库不推送）；</li>
 *   <li>/topic/iot/device-status/{wardId}：设备状态事件经 fy.topic 往返（发布→自消费）后的
 *       状态帧（调用方 IotFanoutListener；载荷 wardId 为空时跳过推送）。</li>
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
     *              wardId 可空——为空时 info 跳过推送，P0 状态帧契约不含 wardId 属预期场景）
     */
    void pushDeviceStatus(DeviceStatusEvent event);

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
