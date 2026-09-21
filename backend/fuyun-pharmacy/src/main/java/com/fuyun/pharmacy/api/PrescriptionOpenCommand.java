package com.fuyun.pharmacy.api;

import java.util.List;

/**
 * 开方命令（跨模块 api 面载体，与 pharmacy dto/PrescriptionCreateRequest 镜像同构——dto 禁外引，
 * backend 宪法 A.7 职责隔离：请求与契约载体各自建模）。组件清单为 Task 12 IT 依赖的冻结面。
 *
 * @param patientId        患者主索引，非空；来源：调用方 visit 上下文服务端解析
 * @param visitId          CF-3 门诊就诊号（O 型 14 位），非空
 * @param rxType           处方类型 code（OUTPATIENT/EMERGENCY），非空
 * @param deptCode         开方科室 code，可空；来源：调用方 visit 上下文
 * @param diagnosisCodes   诊断 code 集（M01 字典），可空
 * @param skinTestRequired 皮试要求，可空（缺省 false，明细药品需皮试时 create 内自动置 true）
 * @param items            处方明细，非空
 */
public record PrescriptionOpenCommand(
        Long patientId,
        String visitId,
        String rxType,
        String deptCode,
        List<String> diagnosisCodes,
        Boolean skinTestRequired,
        List<Item> items) {

    /**
     * 明细行（与 pharmacy dto/RxItemRequest 八组件一一对应——api 面镜像；quantity DECIMAL string
     * 承载，D-18 同源）。
     *
     * @param drugId     药品 id，非空
     * @param quantity   数量，必填且 &gt;0（DECIMAL string）
     * @param unit       单位，可空（缺省取 drug.unit）
     * @param singleDose 单次剂量，可空
     * @param routeCode  给药途径 code（M01 字典），可空
     * @param frequency  用药频次 code（M01 字典），可空
     * @param days       用药天数，可空
     * @param usageNote  用法备注，可空
     */
    public record Item(
            Long drugId,
            String quantity,
            String unit,
            String singleDose,
            String routeCode,
            String frequency,
            Integer days,
            String usageNote) {}
}
