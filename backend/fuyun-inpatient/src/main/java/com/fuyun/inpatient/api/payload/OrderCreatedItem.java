package com.fuyun.inpatient.api.payload;

/**
 * 医嘱开立明细行（OrderCreatedPayload.items 元素，V901 id 66 desc items[] 子契约冻结组件）：
 * 与医嘱主表同事务保存的子表行，M06 审方与 M13 计价取数共用载荷。
 *
 * @param itemSeq  行序号（同一医嘱内 1 起递增），非空
 * @param itemCode 项目编码（药品/检验/检查等项目字典编码），非空
 * @param itemName 项目名称（冗余承载，消费方免回查），非空
 * @param dosage   剂量（数值+单位拼串形态，如 0.5g），可空——非用药类医嘱无剂量
 * @param unit     计量单位，可空——非用药类医嘱无单位
 * @param route    给药途径（用药类医嘱必带），可空——非用药类医嘱无途径
 * @param quantity 数量（DECIMAL string 承载，pharmacy Line 同口径），非空
 * @param itemType 行项目类型（药品/检验/检查等形态标识），非空
 */
public record OrderCreatedItem(
        int itemSeq,
        String itemCode,
        String itemName,
        String dosage,
        String unit,
        String route,
        String quantity,
        String itemType) {}
