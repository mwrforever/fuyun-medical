package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 绑定请求（POST /cards/bind，FU-M02-04：既有无主卡改挂档案，卡已挂接他档时拒绝）。
 *
 * @param cardNo    卡面号，非空；来源：读卡器/手工录入
 * @param patientId 患者主索引，非空；来源：窗口核验证件后定位档案
 */
public record CardBindRequest(
        @NotBlank String cardNo, @NotNull Long patientId) {}
