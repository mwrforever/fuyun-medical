package com.fuyun.nursing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 出入量明细录入入参（POST /api/v1/nursing/io-records，手工/PDA 录入）。patientId/wardId/
 * occurAt/itemName 不设入参组件：patient/ward 由 IWardMetaService 在区行服务端装配（不信
 * 客户端）；发生时间业务时间一律服务器时间（GC25）；itemName 由服务端按 IoItemCode 词表冗余
 * 落库（禁客户端伪造展示名）。数量 string 承载（D-18 口径），服务端解析为 NUMERIC(10,2)。
 *
 * @param visitId  住院就诊号（I 型 14 位），必填；来源：操作者工作站/PDA 当前患者
 * @param ioType   出入量类型 code（INTAKE/OUTPUT；非法值 NS-1019），必填；来源：录入面选择
 * @param itemCode 项目 code（入量：IV_FLUID/ORAL/NASOGASTRIC/BLOOD；出量：URINE/STOOL/
 *                 VOMIT/DRAINAGE/PUNCTURE；词表外或与 ioType 不一致 NS-1019），必填；来源：录入面选择
 * @param quantity 数量（数字文本，如 "1200.50"；非数字/非正数 NS-1019），必填；来源：计量录入
 * @param unit     单位（空缺省 ml），可空；来源：录入面选择
 * @param source   数据源 code（MANUAL/PDA；空缺省 MANUAL；INFUSION_AUTO 等预留源 P1 拒收
 *                 NS-1019），可空；来源：PDA 端标识/工作站默认
 * @param remark   备注，可空；来源：操作者录入
 */
public record IoRecordCreateRequest(
        @NotBlank(message = "visitId 不能为空") String visitId,
        @NotBlank(message = "ioType 不能为空") String ioType,
        @NotBlank(message = "itemCode 不能为空") String itemCode,
        @NotBlank(message = "quantity 不能为空") String quantity,
        String unit,
        String source,
        String remark) {}
