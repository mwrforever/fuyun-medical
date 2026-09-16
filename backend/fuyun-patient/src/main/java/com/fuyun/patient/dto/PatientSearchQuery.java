package com.fuyun.patient.dto;

/**
 * 患者检索查询条件（GET /patients/search，A.7-1 参数对象化）。
 *
 * @param keyword 检索词（证件号原文/手机号原文/姓名，服务端按形态分派匹配列；明文禁日志）
 * @param page    页码（0 基）
 * @param size    单页条数（1-200）
 */
public record PatientSearchQuery(String keyword, int page, int size) {}
