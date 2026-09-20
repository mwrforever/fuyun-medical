package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.ScheduleStatus;
import com.fuyun.outpatient.enums.SessionType;
import java.time.LocalDate;

/**
 * 排班日历出参（GET /api/v1/outpatient/schedules，M03 FU-M03-01）。record 透明浅不可变载体
 * （backend 宪法 A.1-2）。
 *
 * @param id         排班主键；Long 经全局 Long→String 定制以 JSON 字符串输出
 * @param templateId 母本模板 id；同上以字符串承载
 * @param schedDate  排班日期，非空
 * @param session    门诊时段（MORNING/AFTERNOON/EVENING），非空
 * @param deptCode   开诊科室编码，非空
 * @param doctorId   出诊医生 id，非空
 * @param apptType   号别（GENERAL/EXPERT/SPECIAL_DISEASE/EMERGENCY/REVISIT），非空
 * @param totalQuota 当日总号数（含加号增量），非空
 * @param usedQuota  已用号数（池行聚合展示口径），非空
 * @param room       诊疗室，可空
 * @param status     排班状态（NORMAL/STOPPED），非空
 * @param stopReason 停诊原因（status=STOPPED 时非空），可空
 */
public record ScheduleVO(
        Long id,
        Long templateId,
        LocalDate schedDate,
        SessionType session,
        String deptCode,
        String doctorId,
        ApptType apptType,
        Integer totalQuota,
        Integer usedQuota,
        String room,
        ScheduleStatus status,
        String stopReason) {}
