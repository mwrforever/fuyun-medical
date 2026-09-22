package com.fuyun.nursing.vo;

/**
 * 体征异常项出参（NursingVitalThresholds.abnormalItems 产出元素）：单指标越正常范围的
 * 结构化描述，detail 文本形态冻结「体温 38.6℃ 高于正常范围 36.0–37.2℃」（Task 11 IT 断言锚）；
 * 观察行归集异常分支的行内容即各异常项 detail 拼接。
 *
 * @param itemName 指标名（体温/脉搏/呼吸/收缩压/舒张压/血氧/疼痛），非空
 * @param detail   异常描述文本（含测量值、方向与正常范围），非空；来源：阈值常量表拼装
 */
public record AbnormalItemVO(String itemName, String detail) {}
