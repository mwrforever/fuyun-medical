package com.fuyun.billing.dto;

import com.fuyun.billing.enums.PriceSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 调价草稿请求（POST /api/v1/billing/charge-items/{id}/prices，方案 3.4 调价三步闭环第一步）。
 *
 * <p>record 载体（A.1-2）；非空约束由 controller @Valid 触发，缺失时由全局渲染器输出 400
 * ProblemDetail。草稿不触发生效——生效须显式 POST /price-adjustments/{id}/publish。
 *
 * @param itemCode      院内物价编码（saveDraft 按码取项目，路径 id 仅资源定位），非空 ≤64；来源：物价员录入
 * @param price         新单价（分，≥0，金额红线 BIGINT 分值制），非空；来源：物价批文/协议定价
 * @param effectiveFrom 生效起（发布后自此时刻生效，发布同刻闭旧版本区间），非空；来源：物价员指定
 * @param priceSource   价格来源，可空（缺省落 OFFICIAL_DOC 物价批文）；来源：物价员选择
 * @param approvalNo    批文/协议文号留痕，可空 ≤64；来源：物价批文原件
 */
public record PriceDraftRequest(
        @NotBlank @Size(max = 64) String itemCode,
        @NotNull @PositiveOrZero Long price,
        @NotNull OffsetDateTime effectiveFrom,
        PriceSource priceSource,
        @Size(max = 64) String approvalNo) {}
