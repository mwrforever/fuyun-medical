package com.fuyun.inpatient.api.payload;

import java.time.Instant;

/**
 * 医嘱执行回签事件载荷（inpatient.order.executed，V800 id 47 冻结契约）：执行回签成功
 * （计划 PENDING→EXECUTED + 医嘱头三态推进）后发布；M13 据此确认住院费用（每计划实例
 * 一条，按项计费确认对齐）。脱敏红线：仅定位键与时间线，禁患者姓名/诊断文本。
 *
 * @param m04OrderNo 医嘱号，非空；来源：回签计划关联医嘱业务号
 * @param visitId    住院就诊号（I 型 14 位），非空；来源：医嘱关联就诊
 * @param patientId  患者主索引，非空
 * @param planNo     执行计划号（PL+yyyyMMdd+5 位流水），非空；来源：回签计划行
 * @param executedAt 执行时点（UTC；请求缺省时=服务器时间），非空；与 order_execute_plan.executed_at 同源
 */
public record OrderExecutedPayload(
        String m04OrderNo, String visitId, long patientId, String planNo, Instant executedAt) {}
