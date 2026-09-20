package com.fuyun.outpatient.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 改期请求（POST /appointments/{no}/reschedule，Task 6）：改期=退旧号新（reschedule_of 链，号源
 * 先占新后退旧防两头空，Spec :137）。
 *
 * @param newPoolId 新号源池行 id，非空；来源：可约号源查询选行（号源余量与停诊校验在服务层）
 */
public record RescheduleRequest(@NotNull Long newPoolId) {}
