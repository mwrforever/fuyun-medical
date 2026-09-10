package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 字典条目创建请求入参（POST /api/v1/system/dict-versions/{versionId}/items，BRIEF-PR3-01 §3.2）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。
 *
 * @param itemCode   条目编码，非空（同版本内唯一，冲突由 uk_dict_item_version_code 唯一索引兜底）；来源：用户输入
 * @param itemName   条目名称，非空；来源：用户输入
 * @param parentCode 父条目编码，可空（null=顶层条目）；来源：用户输入
 * @param sort       排序号，可空（null 按 0 处理，小者在前）；来源：用户输入
 */
public record DictItemCreateRequest(
        @NotBlank String itemCode, @NotBlank String itemName, String parentCode, Integer sort) {}
