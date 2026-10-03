package com.fuyun.nursing.api;

import java.time.Instant;

/**
 * 护理不良事件上报事件载荷（nursing.adverse-event.reported，V1109 id 83 冻结契约，Task 10 发布）：
 * 不良事件上报（含匿名通道）时发布，M19 护理质量指标消费（缺位登记——M19 订阅未落，登记位先行）。
 *
 * @param eventNo       不良事件业务号，非空；来源：不良事件域发号器（消费方幂等锚）
 * @param category      事件类别，非空；八类词表（用药错误/跌倒/压疮/管路滑脱/输血/器械/设施/其他，
 *                      词表与 V1107 adverse_event.category 列注释同源）
 * @param severityClass 严重度分级，非空；I/II/III/IV 四级（I 最重，驱动 24 小时上报时限）
 * @param wardId        发生病区编码，非空；来源：上报时所在病区（M01 组织机构病区 code）
 * @param occurredAt    事件发生时点（UTC），非空；来源：上报表单据实填报（非落库时点）
 */
public record AdverseEventReportedPayload(
        String eventNo, String category, String severityClass, String wardId, Instant occurredAt) {}
