package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 门诊申请单开立事件载荷（outpatient.order.created，V204 id 23 冻结契约——V605 占位经 UPDATE 升级正式载荷）：
 * 开单同事务发布，M13（本仓 billing）生成 PENDING 费用（PR-3 已订阅，按 orderId 取 sourceRef）。
 *
 * @param orderId   申请单业务号（order_no，billing sourceRef 直取——主控裁决 3；V605 占位字段名兼容保留）
 * @param visitId   CF-3 门诊就诊号（O+yyyyMMdd+5 位流水，M03 唯一签发）
 * @param patientId 患者主索引
 * @param lines     非药品计费行（检查/检验/治疗等项目行，M13 按行生成 PENDING 费用）
 */
public record OrderCreatedPayload(String orderId, Long patientId, String visitId, List<Line> lines) {

    /** 计费行（quantity 为 DECIMAL string 承载，D-18 同源；itemCode=项目编码锚点） */
    public record Line(String itemCode, String quantity) {}

    /** 顶层组件名清单（契约测试与 V204 id 23 UPDATE 后 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES = List.of("orderId", "patientId", "visitId", "lines");

    /** 行组件名清单（desc 中 lines[]{...} 段逐字同源锚点） */
    public static final List<String> LINE_COMPONENT_NAMES = List.of("itemCode", "quantity");
}
