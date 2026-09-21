package com.fuyun.outpatient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 医生站开单请求（POST /api/v1/outpatient/visits/{visitId}/orders，M03 Spec :154）：orderType 五类
 * 开单词表（EXAM/LAB/TREATMENT/DISPOSAL/MATERIAL）显式必填（RX_REF 不经本端点创建——M06 处方生效
 * 链写入），items 计费行清单非空；quantity 为 DECIMAL string（D-18 同源，服务端显式格式校验
 * OP-1019 拒绝非数字串——W-22⑦ 禁裸 parse，billing 侧 BigDecimal 解析前置防线）。
 *
 * @param orderType 单据类型词表值（EXAM/LAB/TREATMENT/DISPOSAL/MATERIAL），非空；来源：医生站表单
 * @param items     计费行明细（itemCode/quantity/usageSummary），非空非空集；来源：医生站开单界面
 */
public record OrderCreateRequest(
        @NotNull(message = "orderType 不得为空") String orderType,
        @NotEmpty(message = "items 不得为空") @Valid List<OrderItemRequest> items) {}
