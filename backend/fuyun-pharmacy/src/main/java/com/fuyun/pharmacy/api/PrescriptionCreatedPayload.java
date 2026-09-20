package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 处方生效事件载荷（pharmacy.prescription.created，V702 id 24 冻结契约——V605 占位经 UPDATE 升级正式载荷）。
 * 语义即「处方开立生效」（Spec :59）：审方通过（PR-4 预检恒通过级）同事务发布，M03 登记引用、
 * M13（本仓 billing）生成 PENDING 费用；硬拦截驳回不发布本事件（机制上杜绝未审先费）。
 *
 * @param prescriptionId 处方 id（=rx_no 业务号，billing sourceRef 直取——主控裁决 3；V605 占位字段名兼容保留）
 * @param rxNo           处方号（与 prescriptionId 同值的显式业务号锚点，冗余为冻结契约一部分）
 * @param visitId        CF-3 门诊就诊号
 * @param patientId      患者主索引
 * @param lines          药品计费行（item_code+数量+用法摘要，M-4 裁决统一权威时点）
 */
public record PrescriptionCreatedPayload(
        String prescriptionId, String rxNo, String visitId, Long patientId, List<Line> lines) {

    /** 计费行（quantity 为 DECIMAL string 承载；usageSummary=用法摘要，Spec :107） */
    public record Line(String itemCode, String quantity, String usageSummary) {}

    /** 顶层组件名清单（Task 4 契约测试与 V702 id 24 UPDATE 后 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("prescriptionId", "rxNo", "visitId", "patientId", "lines");

    /** 行组件名清单（desc 中 lines[]{...} 段逐字同源锚点） */
    public static final List<String> LINE_COMPONENT_NAMES = List.of("itemCode", "quantity", "usageSummary");
}
