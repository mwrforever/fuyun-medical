package com.fuyun.outpatient.dto;

import com.fuyun.outpatient.enums.ApptType;
import com.fuyun.outpatient.enums.SessionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 排班模板保存请求（POST/PUT /api/v1/outpatient/schedule-templates 共用体，M03 FU-M03-01）：
 * id 为空=登记语义、非空=按 id 全量覆盖请求面字段（PUT 更新语义）。
 *
 * @param id           模板主键；PUT 更新时必填、POST 登记时为空（null）；来源：前端表单回传
 * @param deptCode     开诊科室编码，非空；来源：模板管理表单（M01 组织域科室 code）
 * @param doctorId     出诊医生 id，非空；来源：模板管理表单（sys_employee）
 * @param effFrom      模板生效日（含当日），非空；来源：模板管理表单
 * @param effTo        模板失效日（含当日），可空（null=长期有效）；来源：模板管理表单
 * @param weekPattern  每周出诊位串，7 位 0/1、位序周一~周日（如 1100000=周一/周二），格式由
 *                     @Pattern 显式校验（400）；来源：周选择控件
 * @param session      门诊时段（SessionType），非空；来源：时段下拉
 * @param apptType     号别（ApptType，词表=字典 outpatient.appt-type），非空；来源：号别下拉
 * @param slotStart    号段开始时刻，非空；来源：时段编辑器
 * @param slotEnd      号段结束时刻，非空且须晚于 slotStart（服务端显式守卫 OP-1019）；来源：时段编辑器
 * @param slotQuota    该时段号总数，≥1；来源：模板管理表单
 * @param room         诊疗室，可空；来源：模板管理表单
 * @param releaseDays  T+N 放号周期（天），1~31；来源：放号规则配置
 * @param releaseTime  每日放号时点，可空（null 落库默认 07:00）；来源：放号规则配置
 */
public record ScheduleTemplateSaveRequest(
        Long id,
        @NotBlank(message = "deptCode 不得为空") String deptCode,
        @NotBlank(message = "doctorId 不得为空") String doctorId,
        @NotNull(message = "effFrom 不得为空") LocalDate effFrom,
        LocalDate effTo,

        @NotBlank(message = "weekPattern 不得为空")
        @Pattern(regexp = "^[01]{7}$", message = "weekPattern 须为 7 位 0/1 串（位序周一~周日）")
        String weekPattern,

        @NotNull(message = "session 不得为空") SessionType session,
        @NotNull(message = "apptType 不得为空") ApptType apptType,
        @NotNull(message = "slotStart 不得为空") LocalTime slotStart,
        @NotNull(message = "slotEnd 不得为空") LocalTime slotEnd,

        @NotNull(message = "slotQuota 不得为空") @Min(value = 1, message = "slotQuota 至少为 1")
        Integer slotQuota,

        String room,

        @Min(value = 1, message = "releaseDays 至少为 1") @Max(value = 31, message = "releaseDays 单周期最多 31 天")
        Integer releaseDays,

        LocalTime releaseTime) {}
