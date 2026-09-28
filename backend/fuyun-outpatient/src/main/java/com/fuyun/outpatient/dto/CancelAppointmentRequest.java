package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 退号请求（POST /appointments/{no}/cancel 与 portal 匿名退号共用，Task 6）：原因为必填留痕要素
 * （审计抽查与 appointment.cancelled 事件 reason 组件同源，操作者录入）。BUG-01 收口补介质凭证
 * 字段：portal 免登录退号链路必填（controller 显式校验），工作站鉴权链路不传——两链路共用本 DTO，
 * 禁加 Bean Validation 必填约束（防破坏工作站既有请求形态）。
 *
 * @param reason          退号原因，非空白；来源：操作者/患者录入（事件载荷脱敏红线由发布侧承载）
 * @param credentialType  介质类型词表：ID_CARD 证件号 / VISIT_CARD 就诊卡号；仅 portal 免登录退号
 *                        链路必填（controller 显式判空+词表校验），工作站鉴权链路可空；来源：portal
 *                        退号页输入
 * @param credentialNo    介质原文（证件号/卡面号）；仅 portal 免登录退号链路必填（controller 显式
 *                        判空校验）；仅介质解析生命周期内存活，禁入日志——患者敏感字段脱敏红线；
 *                        来源：portal 退号页输入
 */
public record CancelAppointmentRequest(@NotBlank String reason, String credentialType, String credentialNo) {}
