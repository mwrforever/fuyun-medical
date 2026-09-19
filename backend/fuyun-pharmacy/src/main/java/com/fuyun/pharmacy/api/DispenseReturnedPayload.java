package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 退药受理完成事件载荷（pharmacy.dispense.returned，V702 id 29 冻结契约）：
 * 消费方 M13（本仓 billing）占用回退（fullReturn=true 回 NONE）、退费联动依据
 * （Spec §3.5 先退药后退费——退费权威在 billing 回执，本事件是联动依据）。
 *
 * @param dispenseNo     发药单号（业务号锚点）
 * @param prescriptionId 处方 id（=rx_no 业务号，与 prescription.created 同口径）
 * @param rxNo           处方号
 * @param patientId      患者主索引
 * @param visitId        CF-3 门诊就诊号
 * @param fullReturn     是否整单退药（true=整单，billing 占用回 NONE；false=部分退按行回退）
 * @param lines          退药行（批号+溯源码留痕，占用回退与追溯共用载荷）
 */
public record DispenseReturnedPayload(
        String dispenseNo,
        String prescriptionId,
        String rxNo,
        Long patientId,
        String visitId,
        boolean fullReturn,
        List<Line> lines) {

    /** 退药行（quantity 为 DECIMAL string 承载；batchNo+traceCodes 批次溯源留痕） */
    public record Line(String itemCode, String batchNo, String quantity, List<String> traceCodes) {}

    /** 顶层组件名清单（Task 4 契约测试与 V702 id 29 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("dispenseNo", "prescriptionId", "rxNo", "patientId", "visitId", "fullReturn", "lines");

    /** 行组件名清单（desc 中 lines[]{...} 段逐字同源锚点，与 completed 同型） */
    public static final List<String> LINE_COMPONENT_NAMES = List.of("itemCode", "batchNo", "quantity", "traceCodes");
}
