package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotMetricDictEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * MDC 指标术语字典 mapper：字典登记、存在性校验与清单查询（自然键 metric_code）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描。
 */
@Mapper
public interface IotMetricDictMapper extends BaseMapper<IotMetricDictEntity> {}
