package com.fuyun.outpatient.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 开方入参（POST /visits/{visitId}/prescriptions，M03↔M06 处方衔接动作）：patientId/visitId/
 * deptCode 由 visit 上下文服务端解析（红线 3 同款——身份锚点前端不传），请求仅承载处方内容面；
 * 数量 DECIMAL string 承载（D-18 同源）。词表/格式校验由 pharmacy 既有主链纵深承担
 * （PH-1006/PH-1007），本 DTO 仅做声明式必填约束（A.3-5）。
 *
 * @param rxType           处方类型 code（OUTPATIENT/EMERGENCY，词表外由 pharmacy 主链显式拒 400），必填
 * @param diagnosisCodes   诊断 code 集（M01 字典），可空
 * @param skinTestRequired 皮试要求，可空（缺省 false）
 * @param items            处方明细，必填非空
 */
public record PrescriptionOpenRequest(
        @NotBlank String rxType,
        List<String> diagnosisCodes,
        Boolean skinTestRequired,
        @Valid @NotEmpty List<Item> items) {

    /**
     * 处方明细行（与 pharmacy api PrescriptionOpenCommand.Item 镜像同构——api 面禁外引，
     * backend 宪法 A.7 职责隔离）。
     *
     * @param drugId     药品 id，必填（drug 字典引用）
     * @param quantity   数量，必填且 &gt;0（DECIMAL string 承载）
     * @param unit       单位，可空（缺省取 drug.unit）
     * @param singleDose 单次剂量，可空
     * @param routeCode  给药途径 code（M01 字典），可空
     * @param frequency  用药频次 code（M01 字典），可空
     * @param days       用药天数，可空
     * @param usageNote  用法备注，可空
     */
    public record Item(
            @NotNull Long drugId,
            @NotBlank String quantity,
            String unit,
            String singleDose,
            String routeCode,
            String frequency,
            Integer days,
            String usageNote) {}
}
