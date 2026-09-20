package com.fuyun.pharmacy.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 配药采集单行（pick 请求行，IDispenseService.pick 采集面构件）：处方明细定位 + 追溯码逐盒采集
 * （「无码不结」，2025-07 起合规口径；空码集由服务侧 PH-1006 拒绝）。
 *
 * @param prescriptionItemId 处方明细 id（string 承载雪花 id）
 * @param traceCodes         追溯码集（逐盒采集，非空）
 */
public record PickLine(@NotNull String prescriptionItemId, List<String> traceCodes) {}
