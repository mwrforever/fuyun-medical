package com.fuyun.iot.vo;

import com.fuyun.iot.enums.DeviceStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 病区床位设备状态墙视图（GET /api/v1/iot/dashboard/wards/{wardId} 出参，FU-M14-13 病区视图）：
 * 绑定五元组 + 设备状态 + 最新值三面拼装，按生效绑定行展开（床位维度状态墙，无绑定设备不出墙）。
 *
 * @param wardId 病区 ID（请求回显），非空
 * @param items  状态墙条目清单（绑定 id 升序 = listByWard 输出序），非空；病区无生效绑定为空清单
 */
public record WardDeviceWallVO(Long wardId, List<BedDeviceItem> items) {

    /**
     * 床位设备状态墙单条目（一绑定一行）：绑定五元组（deviceId/patientId/visitId/bedId/wardId——
     * wardId 由外层承载故条目省列）+ 设备档案展示名 + 设备状态 + 最新值清单。
     *
     * @param bedId        床位 id，可空（移动式绑定未落床位）
     * @param deviceId     IoTDA 设备标识，非空
     * @param deviceName   设备名称（设备档案），可空（档案缺失时 null）
     * @param patientId    患者主索引（绑定快照），可空
     * @param visitId      住院就诊号（绑定快照，CF-3 14 位串），可空
     * @param status       设备状态（五态）：优先设备状态快照（fy:iot:snapshot:device-status:*），
     *                     快照缺席回退设备档案 status；档案亦缺失为 null
     * @param lastOnlineAt 最近上线时刻（快照优先，回退档案 lastOnlineAt），可空
     * @param latestValues 设备×指标最新值清单（fy:iot:snapshot:latest:{deviceId}:{metricCode} 快照
     *                     面）；无产品映射或快照全缺席为空清单
     */
    public record BedDeviceItem(
            Long bedId,
            String deviceId,
            String deviceName,
            Long patientId,
            String visitId,
            DeviceStatus status,
            OffsetDateTime lastOnlineAt,
            List<LatestValue> latestValues) {}

    /**
     * 设备×指标最新值（快照「值|毫秒时间戳」管道文本解析产物）。
     *
     * @param deviceId   设备标识，非空
     * @param metricCode 指标编码（MDC），非空
     * @param value      最新采集值，非空（非数值行不进快照面）
     * @param occurredAt 采集发生时刻（快照毫秒时间戳转 UTC），非空
     */
    public record LatestValue(String deviceId, String metricCode, BigDecimal value, OffsetDateTime occurredAt) {}
}
