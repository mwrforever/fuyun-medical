package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.PrescriptionItem;
import java.math.BigDecimal;

/**
 * 处方明细出参（from(entity) 手写映射，DrugVO 同型）。quantity/returnedQuantity 为 DECIMAL
 * string 承载（D-18 同源：{@link BigDecimal#toPlainString()} 直出，计费行与 created 事件共用口径）。
 */
public record PrescriptionItemVO(
        Long id,
        Long drugId,
        String drugCode,
        String itemCode,
        String quantity,
        String unit,
        String singleDose,
        String routeCode,
        String frequency,
        Integer days,
        String usageNote,
        String usageSummary,
        String status,
        String returnedQuantity) {

    /**
     * 实体→出参静态工厂（数量列 toPlainString 直出）。
     *
     * @param entity 明细行，非空
     * @return 出参，非空
     */
    public static PrescriptionItemVO from(PrescriptionItem entity) {
        return new PrescriptionItemVO(
                entity.getId(),
                entity.getDrugId(),
                entity.getDrugCode(),
                entity.getItemCode(),
                entity.getQuantity() == null ? null : entity.getQuantity().toPlainString(),
                entity.getUnit(),
                entity.getSingleDose(),
                entity.getRouteCode(),
                entity.getFrequency(),
                entity.getDays(),
                entity.getUsageNote(),
                entity.getUsageSummary(),
                entity.getStatus(),
                entity.getReturnedQuantity() == null
                        ? null
                        : entity.getReturnedQuantity().toPlainString());
    }
}
