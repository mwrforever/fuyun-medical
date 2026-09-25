package com.fuyun.inpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.inpatient.entity.MedicalOrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 住院医嘱明细 mapper：单表链式能力（主子表同事务落库与按医嘱号集合查询；无独立条件更新
 * ——明细行状态面由计费回执联动刷新，归 billing 消费任务）。行序号组内唯一性由应用层
 * 1 起递增生成保证（开立服务单一写入口）。
 */
@Mapper
public interface MedicalOrderItemMapper extends BaseMapper<MedicalOrderItem> {}
