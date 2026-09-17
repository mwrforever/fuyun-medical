package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.HealthItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 健康档案明细 mapper：明细行读写（含纠错链行；ACTIVE 过敏项快速校验主路径走本 mapper）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface HealthItemMapper extends BaseMapper<HealthItem> {}
