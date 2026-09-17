package com.fuyun.patient.api;

/**
 * 过敏项契约载体（供 M06 审方/开单嵌查与 M05 护理执行场景）。
 *
 * @param itemId   健康档案项 id；来源：patient.health_item
 * @param itemCode 过敏物字典 code（可空：手工录入无字典对照）；来源：M01 字典引用
 * @param itemName 过敏物名称（录入原文）；来源：临床录入
 * @param severity 严重程度 MILD/MODERATE/SEVERE（可空）；来源：临床录入
 */
public record AllergyItem(long itemId, String itemCode, String itemName, String severity) {}
