package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.DispenseItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调剂明细 mapper：单表操作经 BaseMapper 链式能力（按 dispense_id 装载/回写均走链式条件）。
 */
@Mapper
public interface DispenseItemMapper extends BaseMapper<DispenseItem> {}
