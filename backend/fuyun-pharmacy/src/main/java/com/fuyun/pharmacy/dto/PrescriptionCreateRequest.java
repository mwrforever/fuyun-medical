package com.fuyun.pharmacy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 开方入参（POST /prescriptions，Spec :169；调用方 M03/M04 出院带药（P2）——PR-4 IT 直调模拟 M03）。
 * 操作者（开方医生）由后端登录上下文注入，前端不传（红线 3 同款）。
 *
 * @param patientId        患者主索引，必填且 >0
 * @param visitId          CF-3 门诊就诊号（O 型 14 位），必填
 * @param rxType           处方类型 code（RxType），必填（PR-4 仅 OUTPATIENT/EMERGENCY）
 * @param deptCode         开方科室 code，可空
 * @param diagnosisCodes   诊断 code 集（M01 字典），可空
 * @param skinTestRequired 皮试要求（请求侧显式），可空（默认 false，明细药品需皮试时自动置 true）
 * @param items            处方明细，必填非空
 */
public record PrescriptionCreateRequest(
        @NotNull Long patientId,
        @NotBlank String visitId,
        @NotBlank String rxType,
        String deptCode,
        List<String> diagnosisCodes,
        Boolean skinTestRequired,
        @Valid @NotEmpty List<RxItemRequest> items) {}
