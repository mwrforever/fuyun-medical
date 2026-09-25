package com.fuyun.inpatient.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 医嘱闭环追溯出参（GET /api/v1/inpatient/orders/{no}/trace，FU-M04-06 闭环追溯视图）：单条
 * 医嘱开立→审核（含药师）→转抄→各计划执行→停止全环节人/时/果一屏聚合（order_status_log +
 * order_audit + order_transfer_log + order_execute_plan 四源时间线，按发生时点升序稳定排序
 * ——电子病历分级评价 4 级闭环追溯支撑）。脱敏红线：仅操作者工号/状态/结论与定位键，禁患者
 * 姓名/诊断文本。
 *
 * @param orderNo   医嘱号，非空
 * @param visitId   住院就诊号（I 型 14 位），非空
 * @param orderType 医嘱类型 code（OrderType 九值），非空
 * @param orderClass 医嘱分类 code（LONG/STAT），非空
 * @param status    医嘱头当前状态 code（OrderStatus 八态），非空
 * @param entries   追溯时间线（stage+operator+occurredAt+result+detail 五要素行，四源聚合升序），非空
 */
public record OrderTraceVO(
        String orderNo, String visitId, String orderType, String orderClass, String status, List<TraceEntry> entries) {

    /**
     * 追溯时间线行（环节五要素：环节/操作者/时点/结果/补充说明）。
     *
     * @param stage      环节 code（五值词表：ORDERED 开立/AUDIT 审核含药师/TRANSFER 转抄核对/
     *                   PLAN 计划执行/STATUS 状态迁移含停止），非空
     * @param operator   操作者员工 ID（开立=医生/审核=系统或药师/转抄=护士/执行=执行护士），可空（系统行为无操作者）
     * @param occurredAt 发生时点（PLAN 行未执行时=计划时点 plan_time，其余=业务发生时点），非空
     * @param result     结果（审核结论 PASSED/REJECTED、转抄结论、计划状态、迁移目标态等），非空
     * @param detail     补充说明（审核意见/第二核对人/计划号@时点/迁移 from→to 与原因等），可空
     */
    public record TraceEntry(String stage, String operator, OffsetDateTime occurredAt, String result, String detail) {}
}
