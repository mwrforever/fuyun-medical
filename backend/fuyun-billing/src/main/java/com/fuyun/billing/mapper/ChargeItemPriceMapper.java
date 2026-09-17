package com.fuyun.billing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.billing.entity.ChargeItemPrice;
import org.apache.ibatis.annotations.Mapper;

/** 价格版本 mapper：单表操作经 BaseMapper 链式能力（无 XML，宪法 A.4.3-15）。必须标注 @Mapper 供 app 侧扫描。 */
@Mapper
public interface ChargeItemPriceMapper extends BaseMapper<ChargeItemPrice> {}
