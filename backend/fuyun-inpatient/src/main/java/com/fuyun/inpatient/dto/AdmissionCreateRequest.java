package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 住院证登记入参（POST /api/v1/inpatient/admissions）：登记即建单入 WAITING 候床队列；
 * 发号（AD 号）、患者归一/拦截（FROZEN 拒 IP-1003）归服务层。
 *
 * @param patientId        患者主索引（经 PatientContextResolver 归一/拦截，从档输入收敛主档），必填；来源：医生站/登记台自患者卡誊抄
 * @param sourceType       来源 code（OUTPATIENT 门诊转诊/EMERGENCY 急诊/PEIS 体检/OTHER 其他），必填；来源：开证场景
 * @param sourceVisitId    门诊 visit_id 引用（O 型 14 位，词表外拒 IP-1022；sourceType=OUTPATIENT 转诊关联必携，其余场景忽略），可空；来源：M03 转诊单
 * @param targetDeptId     目标科室编码（M01 组织机构 code），可空；来源：开证医生指定
 * @param targetWardId     目标病区编码，可空；来源：开证医生指定
 * @param admissionType    入院类型 code（NORMAL 普通/EMERGENCY 急诊/PRE_HOSPITAL 预住院；EMERGENCY 为队列第一优先键），必填；来源：开证医生判定
 * @param expectDate       预约入院日期（队列排序第二键=预约时段），可空；来源：开证医生预约
 * @param diagnosisSummary 入院诊断摘要（register 时誊写至就诊行；敏感文本禁入事件载荷——脱敏红线），可空；来源：开证医生录入
 * @param issuedDoctorId   开证医生（M01 用户标识），必填；来源：开证医生登录上下文誊抄
 */
public record AdmissionCreateRequest(
        @NotNull(message = "patientId 不能为空") Long patientId,

        @NotBlank(message = "sourceType 不能为空")
        @Pattern(regexp = "OUTPATIENT|EMERGENCY|PEIS|OTHER", message = "sourceType 词表外")
        String sourceType,

        @Pattern(regexp = "^[OI]\\d{13}$", message = "sourceVisitId 须为 14 位 visit_id 结构")
        String sourceVisitId,

        String targetDeptId,
        String targetWardId,

        @NotBlank(message = "admissionType 不能为空")
        @Pattern(regexp = "NORMAL|EMERGENCY|PRE_HOSPITAL", message = "admissionType 词表外")
        String admissionType,

        LocalDate expectDate,

        @Size(max = 255, message = "diagnosisSummary 超长（≤255）")
        String diagnosisSummary,

        @NotBlank(message = "issuedDoctorId 不能为空") String issuedDoctorId) {}
