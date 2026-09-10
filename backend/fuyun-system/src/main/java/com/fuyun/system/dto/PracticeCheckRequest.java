package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * 执业授权校验请求入参（POST /api/v1/system/practice/check，BRIEF-PR3-01 §3.3）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。
 *
 * @param employeeId 员工 ID（sys_employee.id），非空；来源：业务方（工作站/医嘱开立等场景）传入
 * @param grantType  授权类型（如 医师执业范围/手术资质），非空；来源：业务方按校验场景传入
 * @param checkTime  校验时点，可空（null 由服务端默认取当前时刻）——该时点是查询语义入参
 *                   （回溯某时刻的授权有效性）非业务落库时间，不违"禁止前端传业务时间"口径
 */
public record PracticeCheckRequest(
        @NotNull Long employeeId, @NotBlank String grantType, OffsetDateTime checkTime) {}
