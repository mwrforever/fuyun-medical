package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 停诊广播事件载荷（outpatient.schedule.stopped，V204 id 40 冻结契约）：
 * 停诊同事务发布（已约患者改期/退费联动依据，通知触达随 M01 通知中心），M18/M19 消费随 P2。
 *
 * @param scheduleId 排班 id（雪花，停诊对象锚点）
 * @param schedDate  排班日期（yyyyMMdd，string 承载）
 * @param deptCode   开诊科室编码
 * @param doctorId   出诊医生 id
 * @param stopReason 停诊原因（业务留痕，脱敏后承载）
 */
public record ScheduleStoppedPayload(
        Long scheduleId, String schedDate, String deptCode, String doctorId, String stopReason) {

    /** 顶层组件名清单（契约测试与 V204 id 40 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("scheduleId", "schedDate", "deptCode", "doctorId", "stopReason");
}
