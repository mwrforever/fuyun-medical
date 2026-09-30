package com.fuyun.patient.dto;

/**
 * 查阅台账检索条件（GET /privacy-access-logs，A.7-1 参数对象化，PatientSearchQuery 同款）：
 * controller 组装原始请求值，size 越界收敛 1-200 在 service 承载。
 *
 * @param patientId 患者过滤（可空=全量台账）
 * @param page      页码（0 基）
 * @param size      单页条数（原始请求值，service 内收敛 1-200）
 */
public record PrivacyAccessLogQuery(Long patientId, int page, int size) {}
