package com.fuyun.patient.vo;

import java.time.OffsetDateTime;

/**
 * 查阅台账出参（GET /privacy-access-logs）：等保审计主检索投影——谁在何时以何目的看了谁的什么
 * （与 M01 审计互补：M01 记谁动了系统，本表记看了谁的什么）。
 *
 * @param id         台账行 id
 * @param operatorId 操作人（OperatorContextHolder 注入，未认证回退 system）
 * @param patientId  被查阅患者主索引
 * @param accessType 查阅类型 UNMASK_QUERY 明文查阅（ARCHIVE_EXPORT/PANORAMA_VIEW 词表预留不实现）
 * @param purpose    查阅目的（个保法最小必要留痕）
 * @param fields     查阅字段清单（逗号分隔字段词）
 * @param occurredAt 查阅时刻（业务时刻，数据库 DEFAULT now() 维护）
 * @param traceId    全链路追踪号（MDC 取值，可空）
 */
public record PrivacyAccessLogVO(
        Long id,
        String operatorId,
        Long patientId,
        String accessType,
        String purpose,
        String fields,
        OffsetDateTime occurredAt,
        String traceId) {}
