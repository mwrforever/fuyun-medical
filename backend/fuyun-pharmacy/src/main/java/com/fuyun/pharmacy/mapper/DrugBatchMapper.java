package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.DrugBatch;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 批次账 mapper：锁定/扣减/回补/释放四支条件更新（行级原子，Spec §9「批次扣减以行级条件更新
 * + 锁定数表达」的落地件）。返回影响行数 0=可用量不足或并发被抢，调用方一律 PH-1010/回滚，
 * 禁读值后覆写（stock_ledger 只增红线与批次账勾稽前提）。
 */
@Mapper
public interface DrugBatchMapper extends BaseMapper<DrugBatch> {

    /**
     * 配药锁定（配药占用以锁定数表达，未发释放）。
     *
     * @param id  批次 id
     * @param qty 锁定数量，非空且 >0
     * @return 影响行数（0=可用量不足）
     */
    @Update("UPDATE pharmacy.drug_batch SET locked_qty = locked_qty + #{qty} "
            + "WHERE id = #{id} AND deleted = 0 AND status = 'IN_STOCK' AND quantity - locked_qty >= #{qty}")
    int lockQuantity(@Param("id") long id, @Param("qty") BigDecimal qty);

    /**
     * 发药扣减（锁定转出库：现存量与锁定数同步减）。
     *
     * @param id  批次 id
     * @param qty 扣减数量，非空且 >0
     * @return 影响行数（0=锁定数不足——未锁先发违例）
     */
    @Update("UPDATE pharmacy.drug_batch SET quantity = quantity - #{qty}, locked_qty = locked_qty - #{qty} "
            + "WHERE id = #{id} AND deleted = 0 AND locked_qty >= #{qty}")
    int deductLocked(@Param("id") long id, @Param("qty") BigDecimal qty);

    /**
     * 退药回补（实物回库：现存量增加，批次不动状态）。
     *
     * @param id  批次 id
     * @param qty 回补数量，非空且 >0
     * @return 影响行数
     */
    @Update("UPDATE pharmacy.drug_batch SET quantity = quantity + #{qty} " + "WHERE id = #{id} AND deleted = 0")
    int restock(@Param("id") long id, @Param("qty") BigDecimal qty);

    /**
     * 释放锁定（处方作废/发药中明细退场；锁定数不属于数量流水，不落 stock_ledger）。
     *
     * @param id  批次 id
     * @param qty 释放数量，非空且 >0
     * @return 影响行数（0=锁定数不足）
     */
    @Update("UPDATE pharmacy.drug_batch SET locked_qty = locked_qty - #{qty} "
            + "WHERE id = #{id} AND deleted = 0 AND locked_qty >= #{qty}")
    int releaseLock(@Param("id") long id, @Param("qty") BigDecimal qty);
}
