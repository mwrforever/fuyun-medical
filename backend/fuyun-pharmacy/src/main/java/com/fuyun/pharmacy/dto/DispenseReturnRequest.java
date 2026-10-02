package com.fuyun.pharmacy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 退药受理入参（POST /dispense-returns，Spec :171；P2 PR-3 Task 8 扩住院形态）。
 * 门诊形态（dispensePlanNo 空）：dispenseNo/mode/items 三件承载；住院形态（dispensePlanNo
 * 非空）：returnLines 逐行退药面承载，mode/dispenseNo 语义位由住院分流忽略。病区退药开关
 * 校验（nursing_ward_config.return_drug_enabled）归 nursing 侧发起端前置——pharmacy 侧
 * 不读 nursing 表（跨模块读表禁止，衔接面注记）。
 *
 * @param dispenseNo     调剂单号（门诊形态必填），住院形态可空
 * @param mode           受理模式：ISSUED_RETURN 发药后实物退 / DISPENSING_CANCEL 发药中明细退场
 *                       （门诊形态必填），住院形态可空
 * @param items          门诊逐行退药面（退药数/批号/追溯码），住院形态可空
 * @param dispensePlanNo 摆药计划号（住院形态必填——非空即住院分流），可空 ≤32
 * @param returnLines    住院逐行退药面（医嘱明细 itemSeq 锚），住院形态必填非空
 */
public record DispenseReturnRequest(
        String dispenseNo,
        String mode,
        @Valid List<ReturnLine> items,
        @Size(max = 32) String dispensePlanNo,
        @Valid List<InpatientReturnLine> returnLines) {

    /**
     * 退药行（门诊形态）。
     *
     * @param prescriptionItemId 处方明细 id（string 承载）
     * @param returnQuantity     退药数量（DECIMAL string），必填
     * @param traceCodes         追溯码集（ISSUED_RETURN 必填逐码核验；DISPENSING_CANCEL 可空）
     */
    public record ReturnLine(
            @NotNull String prescriptionItemId, @NotBlank String returnQuantity, List<String> traceCodes) {}

    /**
     * 住院退药行（住院形态）。
     *
     * @param itemSeq        医嘱明细序号（order_medication.items 快照 itemSeq，string 承载——
     *                       dispense_item.prescription_item_id 双语义承载面）
     * @param returnQuantity 退药数量（DECIMAL string），必填
     * @param traceCodes     追溯码集，可空（住院摆药无逐盒采集面——非空时按防回流核验）
     */
    public record InpatientReturnLine(
            @NotBlank String itemSeq, @NotBlank String returnQuantity, List<String> traceCodes) {}

    /**
     * 门诊形态便捷构造器（P2 PR-3 Task 8 扩住院字段前既有调用面零改动承载——门诊形态
     * 住院两位恒 null）。
     *
     * @param dispenseNo 调剂单号，非空（门诊形态应用层守卫承载）
     * @param mode       受理模式，非空
     * @param items      门诊逐行退药面，非空（应用层守卫承载）
     */
    public DispenseReturnRequest(String dispenseNo, String mode, List<ReturnLine> items) {
        this(dispenseNo, mode, items, null, null);
    }
}
