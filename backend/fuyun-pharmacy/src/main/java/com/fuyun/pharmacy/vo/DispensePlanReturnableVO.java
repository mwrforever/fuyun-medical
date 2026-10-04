package com.fuyun.pharmacy.vo;

import java.util.List;

/**
 * 住院可退明细读面（GET /api/v1/pharmacy/dispense-plans/{no}/returnable，W-66/D-30：
 * 退药弹窗多行化的后端数据源）。仅 DELIVERED 调剂行可读（与 acceptInpatientReturn 写面
 * 同守卫语义）；明细=NORMAL 行全列（住院退药一次性受理，缺行守卫要求逐行交代——
 * returnedQty 首退恒 0，防御性携带 returnableQty 供前端禁输）。patientId 沿 pharmacy
 * 出参 Long 惯例（DispenseVO/DispensePlanVO 同源），出网经 int64→string 覆写器钉精度。
 */
public record DispensePlanReturnableVO(
        String planNo,
        String dispenseNo,
        String dispenseStatus,
        Long patientId,
        String visitId,
        String wardId,
        List<ReturnableItem> items) {

    /**
     * 可退明细行：itemSeq=医嘱明细序号锚（prescription_item_id 双语义承载），数量 DECIMAL string 承载。
     *
     * @param itemSeq       医嘱明细序号锚（string 化），非空
     * @param itemCode      收费项目 code，非空
     * @param batchNo       批号（回补勾稽锚），非空
     * @param issuedQty     实发数量（DECIMAL string），非空
     * @param returnedQty   累计已退数量（DECIMAL string，首退恒 "0"），非空
     * @param returnableQty 可退净量=实发-已退（DECIMAL string，前端禁输上限），非空
     */
    public record ReturnableItem(
            String itemSeq,
            String itemCode,
            String batchNo,
            String issuedQty,
            String returnedQty,
            String returnableQty) {}
}
