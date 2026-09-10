package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotConsumeErrorLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消费错误日志 mapper：毒丸留痕落库唯一写入口（应用层仅 INSERT，重放/放弃处置随 P1）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotConsumeErrorLogMapper extends BaseMapper<IotConsumeErrorLogEntity> {}
