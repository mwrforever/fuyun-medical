package com.fuyun.inpatient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 入院登记确认入参（POST /api/v1/inpatient/admissions/{no}/register）：同事务签发 I 型
 * visit_id（红线 1）并登记医保类型；患者可用性经 M02 解析（FROZEN 拒 IP-1003）归服务层。
 *
 * @param insuranceType 医保类型（险种标识，M01 字典 code；随 inpatient.visit.registered 外发），必填；来源：登记台核验医保凭证
 */
public record VisitRegisterRequest(
        @NotBlank(message = "insuranceType 不能为空") @Size(max = 32, message = "insuranceType 超长（≤32）")
        String insuranceType) {}
