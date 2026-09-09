package com.fuyun.system.vo;

/**
 * 字典条目出参（创建响应与版本读接口条目元素共用）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）。
 *
 * @param itemCode   条目编码，非空
 * @param itemName   条目名称，非空
 * @param parentCode 父条目编码，可空（null=顶层条目）
 * @param sort       排序号（读接口按升序返回，小者在前）
 */
public record DictItemVO(String itemCode, String itemName, String parentCode, Integer sort) {}
