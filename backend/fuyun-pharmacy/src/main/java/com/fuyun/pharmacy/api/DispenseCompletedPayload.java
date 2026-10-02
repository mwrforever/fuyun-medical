package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 发药完成事件载荷（pharmacy.dispense.completed，V702 id 28 冻结 + V1111 契约演进）：
 * 消费方 M03 状态聚合（PR-5 订阅）、M13（本仓 billing）执行占用标记 DISPENSED（PR-4 已接线）；
 * 住院摆药行增可空字段 m04OrderNo/wardId/dispensePlanNo（M05 签收衔接消费子集，V1111 只增不删）。
 *
 * @param dispenseNo     发药单号（业务号锚点）
 * @param prescriptionId 处方 id（=rx_no 业务号，与 prescription.created 同口径）
 * @param rxNo           处方号
 * @param patientId      患者主索引
 * @param visitId        就诊号双语义承载：门诊行 O 型就诊号 / 住院行 I 型 14 位就诊号（与
 *                       dispense.visit_id 列双语义声明同源，非 V1111 新增组件）
 * @param dispenseType   发药类型（DispenseType code——门诊 OUTPATIENT / 住院 INPATIENT_DOSE/INPATIENT_PIVA）
 * @param lines          发药行（批号+溯源码留痕，占用标记与追溯共用载荷）
 * @param m04OrderNo     住院医嘱号（V1111 住院衔接可空字段；门诊行 null——M05 摆药计划关联锚）
 * @param wardId         病区编码（V1111 住院衔接可空字段；门诊行 null——M05 签收衔接归属病区）
 * @param dispensePlanNo 摆药计划号（V1111 住院衔接可空字段；门诊行 null——回链 dispense_plan.plan_no）
 */
public record DispenseCompletedPayload(
        String dispenseNo,
        String prescriptionId,
        String rxNo,
        Long patientId,
        String visitId,
        String dispenseType,
        List<Line> lines,
        String m04OrderNo,
        String wardId,
        String dispensePlanNo) {

    /** 发药行（quantity 为 DECIMAL string 承载；batchNo+traceCodes 批次溯源留痕） */
    public record Line(String itemCode, String batchNo, String quantity, List<String> traceCodes) {}

    /** 顶层组件名清单（契约测试与 V702 id 28 desc + V1111 追加句逐字同源锚点；前七为冻结原序，后三为住院追加） */
    public static final List<String> COMPONENT_NAMES = List.of(
            "dispenseNo",
            "prescriptionId",
            "rxNo",
            "patientId",
            "visitId",
            "dispenseType",
            "lines",
            "m04OrderNo",
            "wardId",
            "dispensePlanNo");

    /** 行组件名清单（desc 中 lines[]{...} 段逐字同源锚点） */
    public static final List<String> LINE_COMPONENT_NAMES = List.of("itemCode", "batchNo", "quantity", "traceCodes");
}
