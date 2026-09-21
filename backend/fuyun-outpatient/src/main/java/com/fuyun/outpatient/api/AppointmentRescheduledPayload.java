package com.fuyun.outpatient.api;

import java.util.List;

/**
 * 改期完成事件载荷（outpatient.appointment.rescheduled，V204 id 38 冻结契约）：
 * 改期同事务发布（reschedule_of 链，号源先占新后退旧防两头空），M18 患者端同步依据（订阅随 P2）。
 *
 * @param oldApptNo     原预约单业务号（reschedule_of 链溯源头）
 * @param newApptNo     新预约单业务号（改期落新单）
 * @param patientId     患者主索引
 * @param newSchedDate  新排班日期（yyyyMMdd，string 承载）
 * @param newSlotStart  新时段起始（HHmm 或 HH:mm:ss，与排班槽位口径同源）
 */
public record AppointmentRescheduledPayload(
        String oldApptNo, String newApptNo, Long patientId, String newSchedDate, String newSlotStart) {

    /** 顶层组件名清单（契约测试与 V204 id 38 desc 逐字同源锚点） */
    public static final List<String> COMPONENT_NAMES =
            List.of("oldApptNo", "newApptNo", "patientId", "newSchedDate", "newSlotStart");
}
