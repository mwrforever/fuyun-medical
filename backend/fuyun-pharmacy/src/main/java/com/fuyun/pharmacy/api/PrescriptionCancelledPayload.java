package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 处方作废事件载荷（pharmacy.prescription.cancelled，V702 id 26 冻结契约）：
 * 未缴费作废场景（费用作废经进程内 PrescriptionFeePort 同事务承载，billing 不订阅本事件——
 * 主控裁决 7）；M03 引用状态联动消费随 PR-5。
 *
 * @param prescriptionId 处方 id（=rx_no 业务号）
 * @param rxNo           处方号
 * @param patientId      患者主索引
 * @param visitId        CF-3 门诊就诊号
 * @param reason         作废原因（必填留痕）
 */
public record PrescriptionCancelledPayload(
        String prescriptionId, String rxNo, Long patientId, String visitId, String reason) {

    /** 顶层组件名清单（V702 id 26 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("prescriptionId", "rxNo", "patientId", "visitId", "reason");
}
