package com.fuyun.iot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuyun.iot.entity.IotBindingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备绑定 mapper：遥测入库的绑定快照批量查询（deviceId in + status=BOUND，拒 N+1）。
 *
 * <p>必须标注 {@code @Mapper}：app 侧 MybatisPlusConfig 的 @MapperScan 按注解过滤扫描
 * （basePackages=com.fuyun 一次覆盖全部模块）。
 */
@Mapper
public interface IotBindingMapper extends BaseMapper<IotBindingEntity> {}
