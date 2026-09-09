package com.fuyun.system.vo;

/**
 * 字典类型出参（创建成功响应）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。id 为 Long，经全局 Long→String 定制
 * 以 JSON 字符串输出（A.3-8）。
 *
 * @param id               字典类型 ID（雪花 ID），非空；JSON 输出为字符串
 * @param typeCode         字典类型编码，非空
 * @param typeName         字典类型名称，非空
 * @param nationalStandard 国标字典标记（true=编码不可修改，FU-M01-06）
 * @param remark           备注，可空
 */
public record DictTypeVO(Long id, String typeCode, String typeName, Boolean nationalStandard, String remark) {}
