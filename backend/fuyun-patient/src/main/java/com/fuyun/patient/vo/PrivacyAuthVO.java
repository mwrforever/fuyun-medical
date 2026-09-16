package com.fuyun.patient.vo;

import java.time.OffsetDateTime;

/**
 * 隐私授权出参（GET/POST /privacy-auths）：status 为读侧派生态（到期自动语义，零定时任务——
 * EXPIRED 由 valid_to 相对当前时刻派生，派生口径见 PrivacyAuthServiceImpl.deriveStatus）。
 *
 * @param id            授权行 id
 * @param patientId     患者主索引
 * @param authType      授权类型 INFORMED_CONSENT/SENSITIVE_USE/GUARDIAN
 * @param authBasis     授权依据引用（纸质凭证编号/电子签名引用）
 * @param scope         授权范围说明（可空）
 * @param signedAt      签署时刻
 * @param validTo       失效时刻（空=长期有效）
 * @param derivedStatus 派生状态：库值 REVOKED 原样；EFFECTIVE 且 valid_to 非空且早于当前时刻为 EXPIRED；其余原值
 */
public record PrivacyAuthVO(
        Long id,
        Long patientId,
        String authType,
        String authBasis,
        String scope,
        OffsetDateTime signedAt,
        OffsetDateTime validTo,
        String derivedStatus) {}
