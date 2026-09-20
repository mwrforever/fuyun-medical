package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 门诊发药完成事件载荷（pharmacy.dispense.completed，V702 id 28 冻结契约）：
 * 消费方 M03 状态聚合（PR-5 订阅）、M13（本仓 billing）执行占用标记 DISPENSED（PR-4 已接线）。
 *
 * @param dispenseNo     发药单号（业务号锚点）
 * @param prescriptionId 处方 id（=rx_no 业务号，与 prescription.created 同口径）
 * @param rxNo           处方号
 * @param patientId      患者主索引
 * @param visitId        CF-3 门诊就诊号
 * @param dispenseType   发药类型（窗口/机发等，Task 5 发药域枚举取值）
 * @param lines          发药行（批号+溯源码留痕，占用标记与追溯共用载荷）
 */
public record DispenseCompletedPayload(
        String dispenseNo,
        String prescriptionId,
        String rxNo,
        Long patientId,
        String visitId,
        String dispenseType,
        List<Line> lines) {

    /** 发药行（quantity 为 DECIMAL string 承载；batchNo+traceCodes 批次溯源留痕） */
    public record Line(String itemCode, String batchNo, String quantity, List<String> traceCodes) {}

    /** 顶层组件名清单（Task 4 契约测试与 V702 id 28 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("dispenseNo", "prescriptionId", "rxNo", "patientId", "visitId", "dispenseType", "lines");

    /** 行组件名清单（desc 中 lines[]{...} 段逐字同源锚点） */
    public static final List<String> LINE_COMPONENT_NAMES = List.of("itemCode", "batchNo", "quantity", "traceCodes");
}
