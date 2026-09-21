package com.fuyun.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * 执业授权登记请求（POST /api/v1/system/practice/grants，FU-M01-04）。
 *
 * <p>record 透明浅不可变载体（backend 宪法 A.1-2）+ JSR-303 声明式校验（A.3-5）。grantType 词表
 * 服务端显式校验（词表外 400），重复 EFFECTIVE 行由部分唯一索引兜底转 409 SYS-1022。
 *
 * @param employeeId 员工 ID（sys_employee.id），非空；来源：管理端登记表单
 * @param grantType  授权类型词表值（PRESCRIPTION/NARCOTIC/ANTIBIO_NONRESTRICT/ANTIBIO_RESTRICT/ANTIBIO_SPECIAL），非空；来源：管理端登记表单
 * @param legalBasis 法定依据（执业证书号/批文引用等），可空 ≤255；来源：登记表单
 * @param validFrom  生效日（含当日），非空；来源：登记表单
 * @param validTo    失效日（含当日），可空（NULL=长期有效）；来源：登记表单
 */
public record PracticeGrantCreateRequest(
        @NotNull Long employeeId,

        @NotBlank
        @Pattern(
                regexp = "PRESCRIPTION|NARCOTIC|ANTIBIO_NONRESTRICT|ANTIBIO_RESTRICT|ANTIBIO_SPECIAL",
                message = "授权类型词表非法")
        String grantType,

        String legalBasis,
        @NotNull LocalDate validFrom,
        LocalDate validTo) {}
