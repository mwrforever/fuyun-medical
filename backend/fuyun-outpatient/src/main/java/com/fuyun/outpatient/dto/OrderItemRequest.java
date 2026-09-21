package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 医生站开单计费行入参（OrderCreateRequest.items 元素）：quantity 为 DECIMAL string 文本承载
 * （D-18 同源；服务端显式数字格式校验 OP-1019——billing 侧 BigDecimal 解析的前置防线）。
 *
 * @param itemCode     项目编码（M13 物价库 code，红线不自建价格），非空白；来源：医生站项目选择
 * @param quantity     数量（DECIMAL string，如 "2"/"0.5"），非空白；来源：医生站开单界面
 * @param usageSummary 用法摘要（频次/途径/注意事项快照），可空；来源：医生站医嘱录入
 */
public record OrderItemRequest(
        @NotBlank(message = "itemCode 不得为空白") String itemCode,
        @NotBlank(message = "quantity 不得为空白") String quantity,
        String usageSummary) {}
