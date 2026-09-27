package com.fuyun.iot.service;

import com.fuyun.iot.vo.DashboardSummaryVO;
import com.fuyun.iot.vo.WardDeviceWallVO;

/**
 * IoT 运营大屏数据面服务（FU-M14-13，P2 PR-2 Task 11）：全院摘要聚合（六项来源）+ Redis 快照
 * 缓存 + 病区床位设备状态墙。REST 快照兜底与 WS 推送载荷共用 {@link DashboardSummaryVO}（同构
 * 契约，Spec「WebSocket 主题实时刷新 + REST 快照兜底」）。
 *
 * <p>缓存形态（brief 冻结）：Redis {@code fy:iot:snapshot:dashboard} TTL 5s（String JSON 承载，
 * StringRedisTemplate 禁 JDK 序列化）——大屏轮询频度下读路径不触库；缓存缺席/损坏/Redis 异常
 * 均降级为直算（缓存可重建，聚合面可降级，DB 为权威数据源）。
 *
 * <p>WS 推送形态（brief「summary 变更触发或 10s 心跳刷新」二选一，实测结论取变更触发）：仓库
 * 既有 @Scheduled 先例（EventOpsJob 等）均为业务 Job 且挂 ShedLock 多实例互斥——照抄做心跳推送
 * 将在互斥下仅单实例发帧（内存 SimpleBroker 其余实例会话收不到），且推送面无既有心跳先例；
 * 故本服务提供 {@link #refreshAndPushIfChanged}（事件驱动变更检测推送，真栈心跳形态留 Task 18
 * 验证注记）。
 */
public interface IDashboardService {

    /**
     * 全院运营摘要（read-through 缓存）：缓存命中直返；缺席/损坏/Redis 异常降级直算并回写缓存
     * （TTL 5s）。聚合六项来源：设备在线/离线/总数（iot_device 状态计数）、活跃告警数
     * （iot_alarm.status=ACTIVE）、风暴态横幅（SCAN fy:iot:alarm:storm:* 任一在挂）、积压水位
     * （iot_consumer_stat 本地消费组最新快照）、质量分（iot_data_quality_stat 当日 UTC 平均分）。
     *
     * @return 全院摘要视图，非空
     */
    DashboardSummaryVO summary();

    /**
     * 事件驱动的摘要刷新与变更触发推送（IotFanoutListener 扩订阅调用点——设备状态变更/告警关闭
     * 自事件消费后调起）：旁路缓存直算最新摘要，与缓存承载的上次摘要比对——变更（含缓存缺席的
     * 首次基准）即经 {@code ITelemetryPushService#pushDashboardSummary} 推
     * /topic/iot/dashboard/global，随后无论变更与否均回写缓存（续期 5s TTL 保持热缓存）。
     *
     * <p>推送失败原样上抛：由消费监听器按业务失败处置（幂等范式③失败收尾后有界重试），与既有
     * 推送面降级口径一致。
     */
    void refreshAndPushIfChanged();

    /**
     * 病区床位设备状态墙（绑定五元组 + 设备状态 + 最新值三面拼装，无缓存直算——病区视图轮询
     * 频度低于全院摘要且行数量级可控）。
     *
     * @param wardId 病区 ID，非空；来源：路径变量
     * @return 状态墙视图（绑定 id 升序），非空；病区无生效绑定为空条目清单
     */
    WardDeviceWallVO wardWall(Long wardId);
}
