package com.fuyun.iot.vo;

import java.math.BigDecimal;

/**
 * 全院运营大屏摘要视图（GET /api/v1/iot/dashboard/summary 出参 + /topic/iot/dashboard/global
 * WS 推送载荷，FU-M14-13）：六项聚合——设备在线/离线/告警活跃/风暴态/积压水位/质量分。
 *
 * <p>纯数据 record（无生成时刻等易变字段）：record 相等即 summary 语义相等，「summary 变更触发」
 * 的 WS 推送以本对象 equals 判定（序列化字段序由 record 声明序固定，缓存 JSON 比对确定可重复）。
 *
 * @param deviceTotal      设备档案总数（@TableLogic 过滤后），非空
 * @param onlineCount      在线设备数（iot_device.status=ONLINE），非空
 * @param offlineCount     离线设备数（iot_device.status=OFFLINE），非空；其余三态（INACTIVE/
 *                         ABNORMAL/DISABLED）不计入在线/离线，与总数差值为其余态量
 * @param activeAlarmCount 活跃告警数（iot_alarm.status=ACTIVE，含未确认与已确认未关闭），非空
 * @param stormActive      风暴态横幅：存在任一 {@code fy:iot:alarm:storm:*} 键即 true（SCAN
 *                         形态；Redis 异常降级 false——横幅缺失不阻断大屏），非空
 * @param backlogEstimate  积压水位（iot_consumer_stat 本地消费组最新快照 backlog_estimate，0~1
 *                         攒批队列填充率口径）；无快照为 null
 * @param qualityScore     质量分（iot_data_quality_stat 当日 UTC 全院平均 quality_score 0~100）；
 *                         当日无统计行（惰性重算未触发）为 null
 */
public record DashboardSummaryVO(
        long deviceTotal,
        long onlineCount,
        long offlineCount,
        long activeAlarmCount,
        boolean stormActive,
        BigDecimal backlogEstimate,
        BigDecimal qualityScore) {}
