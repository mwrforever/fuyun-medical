package com.fuyun.pharmacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.pharmacy.entity.OrderMedication;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院医嘱用药快照 mapper：单表链式能力（插入/按医嘱号查询/快照刷新），幂等唯一约束
 * uk_medication_order_no 由 DDL 兜底（mapper 侧无条件更新通道——快照行仅 items 刷新与
 * 逻辑删两条写路径，均走链式 updateById）。
 */
@Mapper
public interface OrderMedicationMapper extends BaseMapper<OrderMedication> {}
