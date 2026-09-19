package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.PrescriptionItem;
import org.apache.ibatis.annotations.Mapper;

/** 处方明细单表 mapper（计费行快照读写，单表链式能力）。 */
@Mapper
public interface PrescriptionItemMapper extends BaseMapper<PrescriptionItem> {}
