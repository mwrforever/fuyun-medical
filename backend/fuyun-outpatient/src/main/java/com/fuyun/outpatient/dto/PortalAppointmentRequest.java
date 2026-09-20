package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * portal 匿名预约请求（POST /api/v1/outpatient/portal/appointments，裁决 13 免登录通道）：
 * 服务端经介质解析（credentialType+credentialNo → PatientIdentityQuery）换得 patientId 后进入统一
 * 预约主流程（channel 固定 PORTAL，pay_deadline 占位）。
 *
 * @param credentialType 介质类型词表：ID_CARD 证件号 / VISIT_CARD 就诊卡号，非空；来源：portal 预约页
 * @param credentialNo   介质原文（证件号/卡面号），非空；来源：portal 预约页输入（仅解析生命周期内存活，
 *                       禁入日志——患者敏感字段脱敏红线）
 * @param poolId         号源池行 id，非空；来源：portal 号源查询（GET /portal/schedules）选中行
 */
public record PortalAppointmentRequest(
        @NotBlank(message = "credentialType 不能为空") String credentialType,
        @NotBlank(message = "credentialNo 不能为空") String credentialNo,
        @NotNull(message = "poolId 不能为空") Long poolId) {}
