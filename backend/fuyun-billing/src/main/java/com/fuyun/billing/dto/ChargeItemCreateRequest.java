package com.fuyun.billing.dto;

import com.fuyun.billing.enums.ItemClass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 收费项目新建请求（POST /api/v1/billing/charge-items，FU-M13-01 管理面）。
 *
 * <p>record 载体（A.1-2）；非法枚举值经 Jackson 反序列化即 400（声明式契约 A.3-5），
 * 非空约束由 controller @Valid 触发，缺失时由全局渲染器输出 400 ProblemDetail。
 *
 * @param itemCode    院内物价编码（业务唯一，uk 前置拒重），非空 ≤64；来源：物价员录入
 * @param itemName    项目名称，非空 ≤128；来源：物价员录入
 * @param itemClass   项目类别（ItemClass 七值），非空；来源：物价员选择
 * @param unit        计价单位（M01 字典 code 引用），非空 ≤16；来源：物价员选择
 * @param execDeptId  默认执行科室 id（M01 组织 id 引用），可空；来源：物价员选择
 * @param comboFlag   组合项目标记，非空（TRUE 时 price_flag 落 COMBO_ONLY）；来源：物价员选择
 * @param feeCategory 清单费用大类（M01 字典 code 引用），非空 ≤32；来源：物价员选择
 */
public record ChargeItemCreateRequest(
        @NotBlank @Size(max = 64) String itemCode,
        @NotBlank @Size(max = 128) String itemName,
        @NotNull ItemClass itemClass,
        @NotBlank @Size(max = 16) String unit,
        Long execDeptId,
        @NotNull Boolean comboFlag,
        @NotBlank @Size(max = 32) String feeCategory) {}
