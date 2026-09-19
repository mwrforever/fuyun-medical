package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 医保编码对照入参（POST /drugs/{id}/insurance-mapping，Spec :168）。
 *
 * @param nhsaCode       国家医保药品编码，必填（对照权威源 code.nhsa.gov.cn）
 * @param catalogVersion 目录版本，必填（随医保目录动态更新）
 * @param payType        支付属性 code（NhsaPayType），必填
 */
public record InsuranceMappingRequest(
        @NotBlank String nhsaCode,
        @NotBlank String catalogVersion,
        @NotBlank String payType) {}
