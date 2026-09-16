package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 隐私授权登记请求（POST /privacy-auths，FU-M02-06 授权侧）：知情同意之外的授权类型（敏感信息
 * 二次利用单独同意/监护人代管）统一登记入口；建档知情同意走注册事务内 recordInformedConsent。
 *
 * @param patientId   患者主索引，非空；来源：授权登记表单
 * @param authType    授权类型词表 INFORMED_CONSENT/SENSITIVE_USE/GUARDIAN，非空；来源：登记表单选择
 * @param authBasis   授权依据引用（纸质凭证编号/电子签名引用），非空；来源：签署凭证
 * @param scope       授权范围说明（如科研/外送用途），可空
 * @param signedAtIso 签署时刻 ISO-8601 文本（可空；空=落当前时刻）
 * @param validToIso  失效时刻 ISO-8601 文本（可空；空=长期有效，EXPIRED 由读侧按本值派生）
 */
public record PrivacyAuthCreateRequest(
        @NotNull Long patientId,

        @NotBlank @Pattern(regexp = "INFORMED_CONSENT|SENSITIVE_USE|GUARDIAN", message = "授权类型非法")
        String authType,

        @NotBlank String authBasis,
        String scope,
        String signedAtIso,
        String validToIso) {}
