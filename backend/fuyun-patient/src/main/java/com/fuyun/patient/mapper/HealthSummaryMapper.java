package com.fuyun.patient.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.patient.entity.HealthSummary;
import org.apache.ibatis.annotations.Mapper;

/**
 * 健康档案聚合 mapper：摘要行读写（懒创建归写路径，查询路径无行不建行）。
 * 必须标注 {@code @Mapper}：app 侧 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface HealthSummaryMapper extends BaseMapper<HealthSummary> {}
