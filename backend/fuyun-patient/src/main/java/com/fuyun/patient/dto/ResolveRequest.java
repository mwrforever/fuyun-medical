package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 标识解析请求（POST /identifiers/resolve，全院高频入口）。
 *
 * @param identifierType 标识类型（IdentifierType 词表），非空；来源：业务模块持卡场景
 * @param identifierValue 标识值明文（服务端盲索引后等值查；禁日志）；来源：读卡/扫码
 */
public record ResolveRequest(
        @NotBlank String identifierType, @NotBlank String identifierValue) {}
