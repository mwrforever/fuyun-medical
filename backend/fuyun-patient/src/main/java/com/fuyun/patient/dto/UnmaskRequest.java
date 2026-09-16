package com.fuyun.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 明文查阅请求（POST /privacy/unmask，FU-M02-06 全仓唯一明文出口）：服务端逐词校验字段词表
 * （非法词 400），purpose 为个保法最小必要留痕必填项（落查阅台账）。
 *
 * @param patientId 被查阅患者主索引，非空；来源：医护工作站明文查阅交互
 * @param fields    查阅字段词清单（name/idCardNo/mobile/address/birthDate），非空且逐词校验
 * @param purpose   查阅目的，非空 ≤255；来源：查阅人填写的业务理由（留痕依据）
 */
public record UnmaskRequest(
        @NotNull Long patientId,
        @NotEmpty List<@Pattern(regexp = "name|idCardNo|mobile|address|birthDate", message = "查阅字段词非法") String> fields,
        @NotBlank @Size(max = 255) String purpose) {}
