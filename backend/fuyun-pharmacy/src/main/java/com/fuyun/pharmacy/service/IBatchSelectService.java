package com.fuyun.pharmacy.service;

import java.math.BigDecimal;

/**
 * 出库选批服务（Spec §3.4「出库自动选批」：先产先出、近效期先出；主控裁决 2 最小实现）。
 */
public interface IBatchSelectService {

    /**
     * 为发药选批（单批足量约束：可用量不足整批返回 null，拆批分配随 P3 药房管理）。
     *
     * @param drugId     药品 id，非空
     * @param storehouse 库房编码，非空
     * @param quantity   请发数量（基础单位），非空且 >0
     * @return FEFO 首个足量在库批次；无合格批次返回 null（调用方 PH-1010）
     */
    com.fuyun.pharmacy.entity.DrugBatch selectForDispense(long drugId, String storehouse, BigDecimal quantity);
}
