package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 补卡请求（POST /cards/replace，FU-M02-04：旧卡 REPLACED 终态 + 新卡发号绑定同档案）。
 *
 * @param cardNo    旧卡卡面号，非空（须处于 LOST 状态）；来源：窗口受理
 * @param newCardNo 新卡卡面号，非空（与既有卡号重复时拒绝）；来源：新卡读卡/录入
 */
public record CardReplaceRequest(
        @NotBlank String cardNo, @NotBlank String newCardNo) {}
