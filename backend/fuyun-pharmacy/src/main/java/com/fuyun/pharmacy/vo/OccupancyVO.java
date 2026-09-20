package com.fuyun.pharmacy.vo;

import com.fuyun.pharmacy.entity.Dispense;
import com.fuyun.pharmacy.entity.Prescription;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import java.math.BigDecimal;

/**
 * 执行占用出参（GET /medication-occupancy，Spec :172 供 M13 位；billing PR-4 不切——BILL-1017
 * 硬前置维持 exec_occupy_status 列口径，本 API 为 P3 退费前置校验切换面）。处方×明细×发药单
 * 三维投影逐计费行出参；未配药处方发药单维度字段为 null（占用行仍出，退费前置可见）。
 * 数量字段 DECIMAL string 承载（D-18 同源：{@link BigDecimal#toPlainString()} 直出）。
 * 组件清单为 M13 消费面冻结契约（api.d.ts 生成别名对齐）。
 */
public record OccupancyVO(
        String rxNo,
        String itemCode,
        String prescriptionStatus,
        String dispenseNo,
        String dispenseStatus,
        String issuedQuantity,
        String returnedQuantity) {

    /**
     * 处方×明细×发药单→占用行静态工厂（手写投影，PrescriptionVO 同型——关键业务字段禁 MapStruct）。
     * issuedQuantity 口径：P1 发药应发=处方数量（createDispense 入队快照，单批复齐），发药后即
     * 实发；returnedQuantity 口径：退药链回写累计。两者仅在发药单在位（已配药）时出值。
     *
     * @param rx       处方行，非空
     * @param item     处方明细行（占用行粒度=计费行快照），非空
     * @param dispense 活动发药单（uk_dispense_rx_active），可空；未配药为 null 即发药单维度不出
     * @return 占用行，非空
     */
    public static OccupancyVO from(Prescription rx, PrescriptionItem item, Dispense dispense) {
        boolean dispensed = dispense != null;
        return new OccupancyVO(
                rx.getRxNo(),
                item.getItemCode(),
                rx.getStatus(),
                dispensed ? dispense.getDispenseNo() : null,
                dispensed ? dispense.getStatus() : null,
                dispensed && item.getQuantity() != null ? item.getQuantity().toPlainString() : null,
                dispensed && item.getReturnedQuantity() != null
                        ? item.getReturnedQuantity().toPlainString()
                        : null);
    }
}
