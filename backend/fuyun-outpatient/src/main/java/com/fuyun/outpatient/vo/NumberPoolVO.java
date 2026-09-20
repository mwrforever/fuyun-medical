package com.fuyun.outpatient.vo;

import com.fuyun.outpatient.enums.ApptType;
import java.time.LocalTime;

/**
 * 可约号源出参（GET /api/v1/outpatient/number-pools/available，M03 FU-M03-01 余量对外查询，
 * 供全渠道与 M18 复用）。record 透明浅不可变载体（backend 宪法 A.1-2）。
 *
 * @param id         池行主键；Long 经全局 Long→String 定制以 JSON 字符串输出
 * @param scheduleId 所属排班主键；同上以字符串承载
 * @param apptType   号别（GENERAL/EXPERT/SPECIAL_DISEASE/EMERGENCY/REVISIT），非空
 * @param slotStart  号段开始时刻，非空（响应按本值升序）
 * @param slotEnd    号段结束时刻，非空
 * @param totalQuota 号总数（含加号增量），非空
 * @param usedCount  已用号数，非空
 * @param remaining  剩余可约数（totalQuota-usedCount，服务端计算），非空恒 ≥1（查询谓词保证）
 */
public record NumberPoolVO(
        Long id,
        Long scheduleId,
        ApptType apptType,
        LocalTime slotStart,
        LocalTime slotEnd,
        Integer totalQuota,
        Integer usedCount,
        int remaining) {}
