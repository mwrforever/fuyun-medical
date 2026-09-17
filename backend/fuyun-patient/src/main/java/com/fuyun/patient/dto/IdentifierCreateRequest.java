package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 补挂标识请求（POST /patients/{patientId}/identifiers，FU-M02-01 归一后补挂场景）。
 *
 * @param identifierType  标识类型，非空；来源：介质选择
 * @param identifierValue 标识值明文，非空（加密落库禁日志）；来源：介质读取
 * @param cardNo          卡面号（卡类介质必填，服务端校验）；可空
 */
public record IdentifierCreateRequest(
        @NotBlank
        @Pattern(
                regexp =
                        "ID_CARD|PASSPORT|MILITARY_OFFICER|OTHER_LEGAL|INSURANCE_ELECTRONIC|HEALTH_CARD|VISIT_CARD|MEDICAL_RECORD_NO",
                message = "标识类型非法")
        String identifierType,

        @NotBlank String identifierValue,
        String cardNo) {}
