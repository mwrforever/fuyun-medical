package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会诊申请入参（POST /consultations）——独立申请路径（CONSULT 类医嘱钩子建草稿不走本入参）：
 * 申请科室与申请医生权威在服务端（申请科室=就诊 current_dept_id，申请医生=操作者上下文，
 * 禁前端传人），响应时限按紧急程度服务端计算（URGENT +30min / NORMAL +24h，禁前端传时点）。
 *
 * @param visitId  住院就诊号（I 型 14 位），非空；来源：医生站会诊申请单
 * @param toDeptId 受邀科室编码（M01 组织 code），非空；来源：医生站会诊申请单受邀方选择
 * @param urgency  紧急程度（URGENT 急会诊/NORMAL 普通会诊——ConsultationUrgency 词表），
 *                 非空；来源：医生站会诊申请单（词表外服务层拒 IP-1022）
 * @param level    会诊级别（DEPT 科内/HOSPITAL 院内/MDT 多学科——ConsultationLevel 词表），
 *                 可空缺省 DEPT；MDT 为预留值（P3 完整化）；来源：医生站会诊申请单
 * @param reason   申请原因，可空（≤255 字符）；来源：医生站会诊申请单
 */
public record ConsultationCreateRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotBlank(message = "toDeptId 不能为空") String toDeptId,
        @NotBlank(message = "urgency 不能为空") String urgency,
        String level,
        @Size(max = 255, message = "reason 超长（≤255）") String reason) {}
