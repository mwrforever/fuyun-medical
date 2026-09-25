package com.fuyun.inpatient.vo;

import com.fuyun.inpatient.entity.MedicalOrderItem;
import java.math.BigDecimal;

/**
 * 住院医嘱明细行出参（OrderDetailVO.items 元素）：字段面与 V904 medical_order_item
 * 冻结列面一一对应（计费回执两列一并出参——医嘱闭环追溯对账面）。
 *
 * @param itemSeq      行序号（同一医嘱内 1 起递增），非空
 * @param continueFlag 延续标志（成组医嘱组内延续执行标记），非空
 * @param itemType     行项目类型（DRUG/LAB/EXAM 等），非空
 * @param itemCode     项目编码，非空
 * @param nameSnapshot 项目名称快照，非空
 * @param dosage       剂量（数值字符串），可空——非药品行无剂量
 * @param dosageUnit   剂量单位，可空——非药品行无单位
 * @param route        给药途径（medication.route code），可空——非药品行无途径
 * @param dripRate     滴速，可空——静滴类医嘱携带
 * @param quantity     数量，非空
 * @param execDeptId   执行科室编码，可空
 * @param skinTestFlag 皮试标记，非空
 * @param oralFlag     抢救口头医嘱补录标记，非空
 * @param feePriced    计费回执标记（M13 已计价），非空
 * @param feeStopped   计费截断回执标记（M13 已按停嘱时点截断），非空
 */
public record OrderItemVO(
        Integer itemSeq,
        Boolean continueFlag,
        String itemType,
        String itemCode,
        String nameSnapshot,
        String dosage,
        String dosageUnit,
        String route,
        String dripRate,
        BigDecimal quantity,
        String execDeptId,
        Boolean skinTestFlag,
        Boolean oralFlag,
        Boolean feePriced,
        Boolean feeStopped) {

    /**
     * 实体→出参静态工厂（关键业务字段手写映射，禁 MapStruct——backend 宪法 A.1-8 先例）。
     *
     * @param entity 医嘱明细行，非空
     * @return 出参，非空
     */
    public static OrderItemVO from(MedicalOrderItem entity) {
        return new OrderItemVO(
                entity.getItemSeq(),
                entity.getContinueFlag(),
                entity.getItemType(),
                entity.getItemCode(),
                entity.getNameSnapshot(),
                entity.getDosage(),
                entity.getDosageUnit(),
                entity.getRoute(),
                entity.getDripRate(),
                entity.getQuantity(),
                entity.getExecDeptId(),
                entity.getSkinTestFlag(),
                entity.getOralFlag(),
                entity.getFeePriced(),
                entity.getFeeStopped());
    }
}
