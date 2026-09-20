package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.SessionType;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 排班模板出参（GET/POST/PUT /api/v1/outpatient/schedule-templates，M03 FU-M03-01）。
 * record 透明浅不可变载体（backend 宪法 A.1-2）。
 *
 * @param id          模板主键；Long 经全局 Long→String 定制以 JSON 字符串输出
 * @param deptCode    开诊科室编码，非空
 * @param doctorId    出诊医生 id，非空
 * @param effFrom     模板生效日（含当日），非空
 * @param effTo       模板失效日（含当日），可空（null=长期有效）
 * @param weekPattern 每周出诊位串（7 位 0/1，位序周一~周日），非空
 * @param session     门诊时段（MORNING/AFTERNOON/EVENING），非空
 * @param apptType    号别（GENERAL/EXPERT/SPECIAL_DISEASE/EMERGENCY/REVISIT），非空
 * @param slotStart   号段开始时刻，非空
 * @param slotEnd     号段结束时刻，非空
 * @param slotQuota   该时段号总数，非空
 * @param room        诊疗室，可空
 * @param releaseDays T+N 放号周期（天），非空
 * @param releaseTime 每日放号时点，可空（null=库默认 07:00 口径）
 * @param status      模板状态（ACTIVE/STOPPED），非空（登记路径固定 ACTIVE）
 */
public record ScheduleTemplateVO(
        Long id,
        String deptCode,
        String doctorId,
        LocalDate effFrom,
        LocalDate effTo,
        String weekPattern,
        SessionType session,
        ApptType apptType,
        LocalTime slotStart,
        LocalTime slotEnd,
        Integer slotQuota,
        String room,
        Integer releaseDays,
        LocalTime releaseTime,
        String status) {}
