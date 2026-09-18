package com.fuyun.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 组合项目成员请求（POST /api/v1/billing/charge-items/{id}/combo-components 请求元素，
 * FU-M13-01 管理面）：组合构成全量覆盖式维护的成员载体。
 *
 * @param componentItemId 成员项目 id（charge_item.id 引用），非空；来源：物价员从项目库选择
 * @param defaultQuantity 默认数量（可小数 >0，落 DECIMAL(12,3)），非空；来源：物价员录入
 */
public record ComboComponentRequest(
        @NotNull Long componentItemId,
        @NotNull @DecimalMin(value = "0.001") BigDecimal defaultQuantity) {}
