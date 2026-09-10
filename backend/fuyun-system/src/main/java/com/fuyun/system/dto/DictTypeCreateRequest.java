package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 字典类型创建请求入参（POST /api/v1/system/dict-types，BRIEF-PR3-01 §3.2）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。
 *
 * @param typeCode         字典类型编码，非空且须为小写点分格式（如 gender、icd10.aliagn）；来源：用户输入
 * @param typeName         字典类型名称，非空；来源：用户输入
 * @param nationalStandard 国标字典标记，可空（null 按 false 处理）；true=编码不可修改（FU-M01-06）
 * @param remark           备注，可空；来源：用户输入
 */
public record DictTypeCreateRequest(
        @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*(\\.[a-z0-9-]+)*$", message = "字典类型编码须为小写点分格式")
        String typeCode,

        @NotBlank String typeName,
        Boolean nationalStandard,
        String remark) {}
