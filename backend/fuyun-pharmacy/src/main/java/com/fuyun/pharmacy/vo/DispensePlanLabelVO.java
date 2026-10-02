package com.fuyun.pharmacy.vo;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * PIVAS 贴签数据面出参（GET /api/v1/pharmacy/dispense-plans/{no}/label，P2 PR-3 Task 8）：
 * 打印动作归 M01 打印降级注记——本数据面仅出贴签内容数据（患者/病区/排批/调配核对双人/药品
 * 明细），打印链路随 M01 打印服务接入。患者名经 patient api PatientNameQuery 出脱敏展示名
 * （M02 敏感红线——姓名原文不跨模块）；床位无 pharmacy 侧数据源（床位归属 nursing 投影，
 * 跨模块读表禁止），bedNo 恒 null 以 null 承载留衔接注记（报告 concerns 申报）。
 * 药品批次在 issue 出库时才选定（贴签核对在 verify 前置时点），故贴签「批次」承载=排批号
 * pivasBatchNo（PIVAS 给药时间分批批次号）。
 */
public record DispensePlanLabelVO(
        String planNo,
        String m04OrderNo,
        Long patientId,
        String patientName,
        String visitId,
        String wardId,
        String bedNo,
        String pivasBatchNo,
        OffsetDateTime planTime,
        Long pickedBy,
        Long verifiedBy,
        List<LabelItem> items) {

    /**
     * 贴签药品行（医嘱项明细快照投影——贴签内容 = 药名/剂量/单位/途径/数量）。
     *
     * @param itemCode 收费项目 code
     * @param itemName 药品名称
     * @param dosage   单次剂量
     * @param unit     剂量单位
     * @param route    给药途径
     * @param quantity 摆药数量（DECIMAL string 承载）
     */
    public record LabelItem(
            String itemCode, String itemName, String dosage, String unit, String route, String quantity) {}
}
