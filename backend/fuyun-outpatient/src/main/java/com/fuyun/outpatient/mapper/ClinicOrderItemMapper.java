package com.fuyun.outpatient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.outpatient.entity.ClinicOrderItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 申请单明细行 mapper：单表操作（开单明细落库/按单取行）经 BaseMapper 链式能力，无状态迁移语义
 * （明细行随主单生命周期，零独立更新）。必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的
 * @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface ClinicOrderItemMapper extends BaseMapper<ClinicOrderItem> {}
