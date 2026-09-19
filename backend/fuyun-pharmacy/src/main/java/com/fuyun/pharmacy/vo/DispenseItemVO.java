package com.fuyun.pharmacy.vo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuyun.pharmacy.entity.DispenseItem;
import java.util.List;

/**
 * 调剂明细出参（from(entity) 手写映射，PrescriptionItemVO 同型）。quantity 系 DECIMAL string
 * 承载（D-18 同源：{@link java.math.BigDecimal#toPlainString()} 直出）；traceCodes 为行级
 * JSON 数组文本还原为码集（组件清单为 Task 7/11/12 依赖的冻结面）。
 */
public record DispenseItemVO(
        Long id,
        Long prescriptionItemId,
        String itemCode,
        String requestedQuantity,
        String issuedQuantity,
        String returnedQuantity,
        String batchNo,
        List<String> traceCodes,
        String itemStatus) {

    /** 追溯码 JSON 读写器（vo 展示侧静态复用，无状态线程安全） */
    private static final ObjectMapper TRACE_MAPPER = new ObjectMapper();

    /**
     * 实体→出参静态工厂（数量列 toPlainString 直出、追溯码 JSON 文本还原码集）。
     *
     * @param entity 调剂明细行，非空
     * @return 出参，非空
     * @throws IllegalStateException 行级 traceCodes 非法 JSON（脏数据显式暴露，禁静默吞码）
     */
    public static DispenseItemVO from(DispenseItem entity) {
        return new DispenseItemVO(
                entity.getId(),
                entity.getPrescriptionItemId(),
                entity.getItemCode(),
                entity.getRequestedQty() == null
                        ? null
                        : entity.getRequestedQty().toPlainString(),
                entity.getIssuedQty() == null ? null : entity.getIssuedQty().toPlainString(),
                entity.getReturnedQty() == null ? null : entity.getReturnedQty().toPlainString(),
                entity.getBatchNo(),
                parseTraceCodes(entity.getTraceCodes()),
                entity.getItemStatus());
    }

    /**
     * 行级追溯码 JSON 文本还原码集。
     *
     * @param json 追溯码 JSON 数组文本；null/空白视为未采集（空集）
     * @return 码集；未采集为空集
     * @throws IllegalStateException 非法 JSON（与发药记录不一致类脏数据，禁止静默丢弃）
     */
    private static List<String> parseTraceCodes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return TRACE_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("调剂明细追溯码数据损坏（非 JSON 数组）：traceCodes=" + json, e);
        }
    }
}
