package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.OrderStatusLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院医嘱状态迁移日志 mapper（单表链式，只增 INSERT 面）：两个写入点——状态机迁移留痕
 * （OrderStateMachineServiceImpl.appendStatusLog，一切状态迁移唯一经状态机故留痕随之收口）
 * 与重整留痕（OrderAuditServiceImpl.reorganize，from=to 无迁移动作留痕）。状态字面量与
 * OrderStatus code 逐字同源（V905 列注释词表）。
 */
@Mapper
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLog> {}
