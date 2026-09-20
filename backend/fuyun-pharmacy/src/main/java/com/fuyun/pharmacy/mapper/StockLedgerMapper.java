package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.StockLedger;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存流水 mapper：只增表仅 INSERT 通道（禁 UPDATE/DELETE，Spec 红线 2），查询按 batch_id 走链式条件。
 */
@Mapper
public interface StockLedgerMapper extends BaseMapper<StockLedger> {}
