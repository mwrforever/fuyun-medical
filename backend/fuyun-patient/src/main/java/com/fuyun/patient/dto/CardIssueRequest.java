package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发卡请求（POST /cards/issue，FU-M02-04：新卡发号并绑定档案，卡号即 VISIT_CARD 标识值）。
 *
 * @param patientId 患者主索引，非空；来源：窗口建档/挂号流程
 * @param cardNo    卡面号，非空；来源：读卡器/手工录入
 */
public record CardIssueRequest(
        @NotNull Long patientId, @NotBlank String cardNo) {}
