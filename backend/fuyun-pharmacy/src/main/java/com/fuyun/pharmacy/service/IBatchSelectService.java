package com.fuyun.pharmacy.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.fuyun.pharmacy.entity.DrugBatch;
import java.math.BigDecimal;

/**
 * 出库选批服务（Spec §3.4「出库自动选批」：先产先出、近效期先出；主控裁决 2 最小实现）。
 * 配对纪律（宪法 A.4.3-20）：本服务以 drug_batch 为主表（实现侧已 extends
 * ServiceImpl&lt;DrugBatchMapper, DrugBatch&gt;），接口侧对应 extends IService&lt;DrugBatch&gt;——
 * 主表通用能力（分页/批量/链式查询等默认方法集）复用 IService 契约面。本服务为 FEFO 只读
 * 选批（无状态迁移、无写面），IService 通用写面（save/updateById 等）不承载选批语义。
 */
public interface IBatchSelectService extends IService<DrugBatch> {

    /**
     * 为发药选批（单批足量约束：可用量不足整批返回 null，拆批分配随 P3 药房管理）。
     *
     * @param drugId     药品 id，非空
     * @param storehouse 库房编码，非空
     * @param quantity   请发数量（基础单位），非空且 >0
     * @return FEFO 首个足量在库批次；无合格批次返回 null（调用方 PH-1010）
     */
    DrugBatch selectForDispense(long drugId, String storehouse, BigDecimal quantity);
}
