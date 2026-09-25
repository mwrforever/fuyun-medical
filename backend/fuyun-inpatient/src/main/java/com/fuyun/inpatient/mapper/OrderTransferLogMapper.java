package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.OrderTransferLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院医嘱转抄记录 mapper（V906 order_transfer_log）：单表链式能力——流水只增面仅消费
 * BaseMapper.insert（转抄核对服务落行），无 UPDATE/DELETE 业务面（词表与列注释见 V906）。
 */
@Mapper
public interface OrderTransferLogMapper extends BaseMapper<OrderTransferLog> {}
