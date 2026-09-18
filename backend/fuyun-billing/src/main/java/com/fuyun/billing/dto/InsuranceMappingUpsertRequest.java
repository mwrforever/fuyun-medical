package com.fuyun.billing.dto;

import com.fuyun.billing.enums.InsurancePayType;
import com.fuyun.billing.enums.MapType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 医保对照登记请求（POST /api/v1/billing/insurance-mappings/upsert，FU-M13-01 贯标载体）：
 * 项目级国家医保 22 项编码对照，upsert 单 ACTIVE 行语义（存在则改、无则插）。
 *
 * @param chargeItemId     收费项目 id，非空；来源：物价员从项目库选择
 * @param mapType          对照类型（TREATMENT/DRUG/CONSUMABLE），非空；来源：按项目类别带出
 * @param nhsaCode         国家医保 22 项编码，非空 ≤64；来源：物价员对照国家目录录入
 * @param catalogVersion   目录版本（快照字段），非空 ≤32；来源：物价员录入（如 "2026.0"）
 * @param selfPayRatio     先自付比例（0-1，DECIMAL(5,4)），非空；来源：按支付属性带出可改
 * @param limitPrice       医保限价（分，NULL=无限价），可空 ≥0；来源：国家目录
 * @param insurancePayType 支付属性（CLASS_A/CLASS_B/CLASS_C/SELF_EXPENSE），非空；来源：国家目录
 * @param checkReceipt     对照校验回执摘要（留痕），可空 ≤255；来源：贯标校验通道
 */
public record InsuranceMappingUpsertRequest(
        @NotNull Long chargeItemId,
        @NotNull MapType mapType,
        @NotBlank @Size(max = 64) String nhsaCode,
        @NotBlank @Size(max = 32) String catalogVersion,
        @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal selfPayRatio,
        @PositiveOrZero Long limitPrice,
        @NotNull InsurancePayType insurancePayType,
        @Size(max = 255) String checkReceipt) {}
